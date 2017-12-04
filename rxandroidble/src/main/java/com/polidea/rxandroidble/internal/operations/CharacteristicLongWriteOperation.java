package com.polidea.rxandroidble.internal.operations;


import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.DeadObjectException;
import android.support.annotation.NonNull;

import com.polidea.rxandroidble.ClientComponent;
import com.polidea.rxandroidble.RxBleConnection.WriteOperationAckStrategy;
import com.polidea.rxandroidble.exceptions.BleDisconnectedException;
import com.polidea.rxandroidble.exceptions.BleException;
import com.polidea.rxandroidble.exceptions.BleGattCallbackTimeoutException;
import com.polidea.rxandroidble.exceptions.BleGattCannotStartException;
import com.polidea.rxandroidble.exceptions.BleGattOperationType;
import com.polidea.rxandroidble.internal.DeviceModule;
import com.polidea.rxandroidble.internal.QueueOperation;
import com.polidea.rxandroidble.internal.connection.PayloadSizeLimitProvider;
import com.polidea.rxandroidble.internal.connection.RxBleGattCallback;
import com.polidea.rxandroidble.internal.serialization.QueueReleaseInterface;
import com.polidea.rxandroidble.internal.util.ByteAssociation;
import com.polidea.rxandroidble.internal.util.QueueReleasingEmitterWrapper;

import java.nio.ByteBuffer;
import java.util.UUID;

import javax.inject.Named;

import io.reactivex.CompletableObserver;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.Scheduler;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;
import io.reactivex.observers.DisposableObserver;

import static com.polidea.rxandroidble.internal.util.DisposableUtil.disposableEmitter;

public class CharacteristicLongWriteOperation extends QueueOperation<byte[]> {

    private final BluetoothGatt bluetoothGatt;
    private final RxBleGattCallback rxBleGattCallback;
    private final Scheduler bluetoothInteractionScheduler;
    private final TimeoutConfiguration timeoutConfiguration;
    private final BluetoothGattCharacteristic bluetoothGattCharacteristic;
    private final PayloadSizeLimitProvider batchSizeProvider;
    private final WriteOperationAckStrategy writeOperationAckStrategy;
    private final byte[] bytesToWrite;
    private byte[] tempBatchArray;

    CharacteristicLongWriteOperation(
            BluetoothGatt bluetoothGatt,
            RxBleGattCallback rxBleGattCallback,
            @Named(ClientComponent.NamedSchedulers.BLUETOOTH_INTERACTION) Scheduler bluetoothInteractionScheduler,
            @Named(DeviceModule.OPERATION_TIMEOUT) TimeoutConfiguration timeoutConfiguration,
            BluetoothGattCharacteristic bluetoothGattCharacteristic,
            PayloadSizeLimitProvider batchSizeProvider,
            WriteOperationAckStrategy writeOperationAckStrategy,
            byte[] bytesToWrite) {
        this.bluetoothGatt = bluetoothGatt;
        this.rxBleGattCallback = rxBleGattCallback;
        this.bluetoothInteractionScheduler = bluetoothInteractionScheduler;
        this.timeoutConfiguration = timeoutConfiguration;
        this.bluetoothGattCharacteristic = bluetoothGattCharacteristic;
        this.batchSizeProvider = batchSizeProvider;
        this.writeOperationAckStrategy = writeOperationAckStrategy;
        this.bytesToWrite = bytesToWrite;
    }

    @Override
    protected void protectedRun(final ObservableEmitter<byte[]> emitter, final QueueReleaseInterface queueReleaseInterface) throws Throwable {
        int batchSize = batchSizeProvider.getPayloadSizeLimit();

        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSizeProvider value must be greater than zero (now: " + batchSize + ")");
        }
        final Observable<ByteAssociation<UUID>> timeoutObservable = Observable.error(
                new BleGattCallbackTimeoutException(bluetoothGatt, BleGattOperationType.CHARACTERISTIC_LONG_WRITE)
        );
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytesToWrite);

        final QueueReleasingEmitterWrapper<byte[]> emitterWrapper = new QueueReleasingEmitterWrapper<>(emitter, queueReleaseInterface);
        writeBatchAndObserve(batchSize, byteBuffer)
                .subscribeOn(bluetoothInteractionScheduler)
                .filter(writeResponseForMatchingCharacteristic(bluetoothGattCharacteristic))
                .take(1)
                .timeout(
                        timeoutConfiguration.timeout,
                        timeoutConfiguration.timeoutTimeUnit,
                        timeoutConfiguration.timeoutScheduler,
                        timeoutObservable
                )
                .repeatWhen(bufferIsNotEmptyAndOperationHasBeenAcknowledgedAndNotUnsubscribed(
                        writeOperationAckStrategy, byteBuffer, emitterWrapper
                ))
                .ignoreElements()
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onComplete() {
                        emitterWrapper.onNext(bytesToWrite);
                        emitterWrapper.onComplete();
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        emitterWrapper.onError(throwable);
                    }
                });
    }

    @Override
    protected BleException provideException(DeadObjectException deadObjectException) {
        return new BleDisconnectedException(deadObjectException, bluetoothGatt.getDevice().getAddress());
    }

    @NonNull
    private Observable<ByteAssociation<UUID>> writeBatchAndObserve(final int batchSize, final ByteBuffer byteBuffer) {
        final Observable<ByteAssociation<UUID>> onCharacteristicWrite = rxBleGattCallback.getOnCharacteristicWrite();
        return Observable.create(
                new ObservableOnSubscribe<ByteAssociation<UUID>>() {
                    @Override
                    public void subscribe(ObservableEmitter<ByteAssociation<UUID>> emitter) throws Exception {
                        final DisposableObserver writeCallbackObserver = onCharacteristicWrite
                                .subscribeWith(disposableEmitter(emitter));
                        emitter.setDisposable(writeCallbackObserver);

                        /*
                         * Since Android OS calls {@link android.bluetooth.BluetoothGattCallback} callbacks on arbitrary background
                         * threads - in case the {@link BluetoothGattCharacteristic} has
                         * a {@link BluetoothGattCharacteristic#WRITE_TYPE_NO_RESPONSE} set it is possible that
                         * a {@link android.bluetooth.BluetoothGattCallback#onCharacteristicWrite} may be called before the
                         * {@link BluetoothGatt#writeCharacteristic(BluetoothGattCharacteristic)} will return.
                         * Because of such a situation - it is important to first establish a full RxJava flow and only then
                         * call writeCharacteristic.
                         */

                        try {
                            final byte[] bytesBatch = getNextBatch(byteBuffer, batchSize);
                            writeData(bytesBatch);
                        } catch (Throwable throwable) {
                            emitter.onError(throwable);
                        }
                    }
                });
    }

    private byte[] getNextBatch(ByteBuffer byteBuffer, int batchSize) {
        final int remainingBytes = byteBuffer.remaining();
        final int nextBatchSize = Math.min(remainingBytes, batchSize);
        if (tempBatchArray == null || tempBatchArray.length != nextBatchSize) {
            tempBatchArray = new byte[nextBatchSize];
        }
        byteBuffer.get(tempBatchArray);
        return tempBatchArray;
    }

    private void writeData(byte[] bytesBatch) {
        bluetoothGattCharacteristic.setValue(bytesBatch);
        final boolean success = bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic);
        if (!success) {
            throw new BleGattCannotStartException(bluetoothGatt, BleGattOperationType.CHARACTERISTIC_LONG_WRITE);
        }
    }

    private static Predicate<ByteAssociation<UUID>> writeResponseForMatchingCharacteristic(
            final BluetoothGattCharacteristic bluetoothGattCharacteristic
    ) {
        return new Predicate<ByteAssociation<UUID>>() {
            @Override
            public boolean test(ByteAssociation<UUID> uuidByteAssociation) {
                return uuidByteAssociation.first.equals(bluetoothGattCharacteristic.getUuid());
            }
        };
    }

    private static Function<Observable<?>, ObservableSource<?>> bufferIsNotEmptyAndOperationHasBeenAcknowledgedAndNotUnsubscribed(
            final WriteOperationAckStrategy writeOperationAckStrategy,
            final ByteBuffer byteBuffer,
            final QueueReleasingEmitterWrapper<byte[]> emitterWrapper) {
        return new Function<Observable<?>, ObservableSource<?>>() {
            @Override
            public ObservableSource<?> apply(Observable<?> emittingOnBatchWriteFinished) throws Exception {
                return emittingOnBatchWriteFinished
                        .takeWhile(notUnsubscribed(emitterWrapper))
                        .map(bufferIsNotEmpty(byteBuffer))
                        .compose(writeOperationAckStrategy);
            }

            @NonNull
            private Function<Object, Boolean> bufferIsNotEmpty(final ByteBuffer byteBuffer) {
                return new Function<Object, Boolean>() {
                    @Override
                    public Boolean apply(Object emittedFromActStrategy) throws Exception {
                        return byteBuffer.hasRemaining();
                    }
                };
            }

            @NonNull
            private Predicate<Object> notUnsubscribed(final QueueReleasingEmitterWrapper<byte[]> emitterWrapper) {
                return new Predicate<Object>() {
                    @Override
                    public boolean test(Object emission) {
                        return !emitterWrapper.isWrappedEmitterUnsubscribed();
                    }
                };
            }
        };
    }
}
