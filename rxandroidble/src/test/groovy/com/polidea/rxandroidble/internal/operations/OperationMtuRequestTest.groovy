package com.polidea.rxandroidble.internal.operations

import android.bluetooth.BluetoothGatt
import com.polidea.rxandroidble.exceptions.BleGattCannotStartException
import com.polidea.rxandroidble.exceptions.BleGattCallbackTimeoutException
import com.polidea.rxandroidble.exceptions.BleGattOperationType
import com.polidea.rxandroidble.internal.serialization.QueueReleaseInterface
import com.polidea.rxandroidble.internal.connection.RxBleGattCallback
import com.polidea.rxandroidble.internal.util.MockOperationTimeoutConfiguration
import io.reactivex.subscribers.TestSubscriber
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.PublishSubject
import spock.lang.Specification

import java.util.concurrent.TimeUnit

public class OperationMtuRequestTest extends Specification {

    static long timeout = 10
    static TimeUnit timeoutTimeUnit = TimeUnit.SECONDS
    QueueReleaseInterface mockQueueReleaseInterface = Mock QueueReleaseInterface
    BluetoothGatt mockBluetoothGatt = Mock BluetoothGatt
    RxBleGattCallback mockGattCallback = Mock RxBleGattCallback
    TestSubscriber<Integer> testSubscriber = new TestSubscriber()
    TestScheduler testScheduler = new TestScheduler()
    PublishSubject<Integer> changedMtuPublishSubject = PublishSubject.create()
    MtuRequestOperation objectUnderTest
    int mtu = 72

    def setup() {
        mockGattCallback.getOnMtuChanged() >> changedMtuPublishSubject
        prepareObjectUnderTest()
    }

    def "should call BluetoothGatt.requestMtu(int) exactly once when run()"() {

        when:
        objectUnderTest.run(mockQueueReleaseInterface).test()

        then:
        1 * mockBluetoothGatt.requestMtu(mtu) >> true
    }

    def "should emit an error if BluetoothGatt.requestMtu(int) returns false"() {

        given:
        mockBluetoothGatt.requestMtu(72) >> false

        when:
        objectUnderTest.run(mockQueueReleaseInterface).test()

        then:
        testSubscriber.assertError BleGattCannotStartException

        and:
        testSubscriber.assertError {
            it.getBleGattOperationType() == BleGattOperationType.ON_MTU_CHANGED
        }

        and:
        1 * mockQueueReleaseInterface.release()
    }

    def "should emit an error if RxBleGattCallback will emit error on RxBleGattCallback.getOnMtuChanged() and release queue"() {

        given:
        mockBluetoothGatt.requestMtu(72) >> true
        objectUnderTest.run(mockQueueReleaseInterface).test()
        def testException = new Exception("test")

        when:
        changedMtuPublishSubject.onError(testException)

        then:
        testSubscriber.assertError(testException)

        and:
        (1.._) * mockQueueReleaseInterface.release() // technically it's not an error to call it more than once
    }

    def "should timeout if will not response after 10 seconds "() {

        given:
        mockBluetoothGatt.requestMtu(72) >> true
        objectUnderTest.run(mockQueueReleaseInterface).test()

        when:
        testScheduler.advanceTimeTo(timeout + 5, timeoutTimeUnit)

        then:
        testSubscriber.assertError(BleGattCallbackTimeoutException)

        and:
        testSubscriber.assertError {
            ((BleGattCallbackTimeoutException)it).getBleGattOperationType() == BleGattOperationType.ON_MTU_CHANGED
        }
    }

    private prepareObjectUnderTest() {
        objectUnderTest = new MtuRequestOperation(mockGattCallback, mockBluetoothGatt,
                new MockOperationTimeoutConfiguration(timeout.intValue(), testScheduler), mtu)
    }
}