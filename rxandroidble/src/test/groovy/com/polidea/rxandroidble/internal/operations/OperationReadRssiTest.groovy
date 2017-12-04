package com.polidea.rxandroidble.internal.operations

import android.bluetooth.BluetoothGatt
import com.polidea.rxandroidble.exceptions.BleGattCallbackTimeoutException
import com.polidea.rxandroidble.exceptions.BleGattCannotStartException
import com.polidea.rxandroidble.exceptions.BleGattOperationType
import com.polidea.rxandroidble.internal.connection.RxBleGattCallback
import com.polidea.rxandroidble.internal.serialization.QueueReleaseInterface
import com.polidea.rxandroidble.internal.util.MockOperationTimeoutConfiguration
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import spock.lang.Specification

import java.util.concurrent.TimeUnit

public class OperationReadRssiTest extends Specification {

    QueueReleaseInterface mockQueueReleaseInterface = Mock QueueReleaseInterface

    BluetoothGatt mockBluetoothGatt = Mock BluetoothGatt

    RxBleGattCallback mockGattCallback = Mock RxBleGattCallback

    TestScheduler testScheduler = new TestScheduler()

    PublishSubject<Integer> onReadRemoteRssiPublishSubject = PublishSubject.create()

    ReadRssiOperation objectUnderTest

    def setup() {
        mockGattCallback.getOnRssiRead() >> onReadRemoteRssiPublishSubject
        prepareObjectUnderTest()
    }

    def "should call BluetoothGatt.readRemoteRssi() exactly once when run()"() {

        when:
        objectUnderTest.run(mockQueueReleaseInterface).subscribe()

        then:
        1 * mockBluetoothGatt.readRemoteRssi() >> true
    }

    def "should emit an error if BluetoothGatt.readRemoteRssi() returns false"() {

        given:
        mockBluetoothGatt.readRemoteRssi() >> false

        when:
        objectUnderTest.run(mockQueueReleaseInterface).subscribe()

        then:
        testSubscriber.assertError BleGattCannotStartException

        and:
        testSubscriber.assertError {
            it.getBleGattOperationType() == BleGattOperationType.READ_RSSI
        }

        and:
        1 * mockQueueReleaseInterface.release()
    }

    def "should emit and error if RxBleGattCallback will emit error on getOnRssiRead() and release queue"() {

        given:
        mockBluetoothGatt.readRemoteRssi() >> true
        def testObserver = objectUnderTest.run(mockQueueReleaseInterface).test()
        def testException = new Exception("test")

        when:
        onReadRemoteRssiPublishSubject.onError(testException)

        then:
        testObserver.assertError(testException)

        and:
        1 * mockQueueReleaseInterface.release()
    }

    def "should emit exactly one value when RxBleGattCallback.getOnRssiRead() emits value"() {

        given:
        def rssi1 = 1
        def rssi2 = 2
        def rssi3 = 3
        mockBluetoothGatt.readRemoteRssi() >> true

        when:
        onReadRemoteRssiPublishSubject.onNext(rssi1)

        then:
        testSubscriber.assertNoValues()

        when:
        objectUnderTest.run(mockQueueReleaseInterface).subscribe()

        then:
        testSubscriber.assertNoValues()

        when:
        onReadRemoteRssiPublishSubject.onNext(rssi2)

        then:
        testSubscriber.assertValue(rssi2)

        and:
        1 * mockQueueReleaseInterface.release()

        when:
        onReadRemoteRssiPublishSubject.onNext(rssi3)

        then:
        testSubscriber.assertValueCount(1) // no more values
    }

    def "should timeout if RxBleGattCallback.onReadRssi() won't trigger in 30 seconds"() {

        given:
        mockBluetoothGatt.readRemoteRssi() >> true
        objectUnderTest.run(mockQueueReleaseInterface).subscribe()

        when:
        testScheduler.advanceTimeBy(30, TimeUnit.SECONDS)

        then:
        testSubscriber.assertError(BleGattCallbackTimeoutException)

        and:
        testSubscriber.assertError {
            ((BleGattCallbackTimeoutException)it).getBleGattOperationType() == BleGattOperationType.READ_RSSI
        }
    }

    private prepareObjectUnderTest() {
        objectUnderTest = new ReadRssiOperation(mockGattCallback, mockBluetoothGatt,
                new MockOperationTimeoutConfiguration(testScheduler))
    }
}