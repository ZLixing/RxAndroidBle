package com.polidea.rxandroidble.internal.serialization;

import android.support.annotation.RestrictTo;

import com.polidea.rxandroidble.ClientComponent;
import com.polidea.rxandroidble.exceptions.BleDisconnectedException;
import com.polidea.rxandroidble.exceptions.BleException;
import com.polidea.rxandroidble.internal.DeviceModule;
import com.polidea.rxandroidble.internal.RxBleLog;
import com.polidea.rxandroidble.internal.connection.ConnectionScope;
import com.polidea.rxandroidble.internal.connection.ConnectionSubscriptionWatcher;
import com.polidea.rxandroidble.internal.connection.DisconnectionRouterOutput;
import com.polidea.rxandroidble.internal.operations.Operation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Named;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Scheduler;
import io.reactivex.functions.Cancellable;
import io.reactivex.observers.DisposableObserver;

@ConnectionScope
public class ConnectionOperationQueueImpl implements ConnectionOperationQueue, ConnectionSubscriptionWatcher {

    private final String deviceMacAddress;
    private final DisconnectionRouterOutput disconnectionRouterOutput;
    private DisposableObserver<BleException> disconnectionThrowableSubscription;
    private final OperationPriorityFifoBlockingQueue queue = new OperationPriorityFifoBlockingQueue();
    private final Future<?> runnableFuture;
    private volatile boolean shouldRun = true;
    private BleException disconnectionException = null;

    @Inject
    ConnectionOperationQueueImpl(
            @Named(DeviceModule.MAC_ADDRESS) final String deviceMacAddress,
            final DisconnectionRouterOutput disconnectionRouterOutput,
            @Named(ClientComponent.NamedExecutors.CONNECTION_QUEUE) final ExecutorService executorService,
            @Named(ClientComponent.NamedSchedulers.BLUETOOTH_INTERACTION) final Scheduler callbackScheduler
    ) {
        this.deviceMacAddress = deviceMacAddress;
        this.disconnectionRouterOutput = disconnectionRouterOutput;
        this.runnableFuture = executorService.submit(new Runnable() {
            @Override
            public void run() {
                QueueSemaphore currentSemaphore;
                while (shouldRun) {
                    try {
                        final FIFORunnableEntry<?> entry = queue.take();
                        final Operation<?> operation = entry.operation;
                        log("STARTED", operation);

                        /*
                         * Calling bluetooth calls before the previous one returns in a callback usually finishes with a failure
                         * status. Below QueueSemaphore is passed to the Operation and is meant to be released
                         * at appropriate time when the next operation should be able to start successfully.
                         */
                        currentSemaphore = new QueueSemaphore();
                        entry.run(currentSemaphore, callbackScheduler);
                        currentSemaphore.awaitRelease();
                        log("FINISHED", operation);
                    } catch (InterruptedException e) {
                        synchronized (ConnectionOperationQueueImpl.this) {
                            if (!shouldRun) {
                                break;
                            }
                        }
                        RxBleLog.e(e, "Error while processing connection operation queue");
                    }
                }

                flushQueue();
                RxBleLog.d("Terminated.");
            }
        });
    }

    private synchronized void flushQueue() {
        while (!queue.isEmpty()) {
            final FIFORunnableEntry<?> entryToFinish = queue.takeNow();
            entryToFinish.operationResultObserver.onError(disconnectionException);
        }
    }

    @Override
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public synchronized <T> Observable<T> queue(final Operation<T> operation) {
        if (!shouldRun) {
            return Observable.error(disconnectionException);
        }

        return Observable.create(new ObservableOnSubscribe<T>() {
            @Override
            public void subscribe(ObservableEmitter<T> emitter) throws Exception {
                final FIFORunnableEntry entry = new FIFORunnableEntry<>(operation, emitter);
                emitter.setCancellable(new Cancellable() {
                    @Override
                    public void cancel() throws Exception {
                        if (queue.remove(entry)) {
                            log("REMOVED", operation);
                        }
                    }
                });

                log("QUEUED", operation);
                queue.add(entry);
            }
        });
    }

    @Override
    public synchronized void terminate(BleException disconnectException) {
        if (this.disconnectionException != null) {
            // already terminated
            return;
        }
        RxBleLog.i("Connection operations queue to be terminated (" + deviceMacAddress + ')');
        shouldRun = false;
        disconnectionException = disconnectException;
        runnableFuture.cancel(true);
    }

    @RestrictTo(RestrictTo.Scope.SUBCLASSES)
    void log(String prefix, Operation operation) {

        if (RxBleLog.isAtLeast(RxBleLog.DEBUG)) {
            RxBleLog.d("%8s %s(%d)", prefix, operation.getClass().getSimpleName(), System.identityHashCode(operation));
        }
    }

    @Override
    public void onConnectionSubscribed() {
        disconnectionThrowableSubscription = disconnectionRouterOutput.asValueOnlyObservable()
                .subscribeWith(new DisposableObserver<BleException>() {
                    @Override
                    public void onComplete() {

                    }

                    @Override
                    public void onNext(BleException bleException) {
                        terminate(bleException);
                    }

                    @Override
                    public void onError(Throwable throwable) {

                    }
                });
    }

    @Override
    public void onConnectionUnsubscribed() {
        disconnectionThrowableSubscription.dispose();
        disconnectionThrowableSubscription = null;
        terminate(new BleDisconnectedException(deviceMacAddress));
    }
}
