package com.polidea.rxandroidble.internal.connection;

import android.bluetooth.BluetoothGatt;

import com.polidea.rxandroidble.ClientComponent;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.internal.ConnectionSetup;
import com.polidea.rxandroidble.internal.serialization.ClientOperationQueue;

import java.util.Set;
import java.util.concurrent.Callable;

import javax.inject.Inject;
import javax.inject.Named;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;

public class ConnectorImpl implements Connector {

    private final ClientOperationQueue clientOperationQueue;
    private final ConnectionComponent.Builder connectionComponentBuilder;
    private final Scheduler callbacksScheduler;

    @Inject
    public ConnectorImpl(
            ClientOperationQueue clientOperationQueue,
            ConnectionComponent.Builder connectionComponentBuilder,
            @Named(ClientComponent.NamedSchedulers.BLUETOOTH_CALLBACKS) Scheduler callbacksScheduler) {
        this.clientOperationQueue = clientOperationQueue;
        this.connectionComponentBuilder = connectionComponentBuilder;
        this.callbacksScheduler = callbacksScheduler;
    }

    @Override
    public Observable<RxBleConnection> prepareConnection(final ConnectionSetup options) {
        return Observable.defer(new Callable<ObservableSource<RxBleConnection>>() {
            @Override
            public ObservableSource<RxBleConnection> call() throws Exception {
                final ConnectionComponent connectionComponent = connectionComponentBuilder
                        .connectionModule(new ConnectionModule(options))
                        .build();

                final Observable<RxBleConnection> newConnectionObservable = Observable.fromCallable(new Callable<RxBleConnection>() {
                    @Override
                    public RxBleConnection call() throws Exception {
                        // BluetoothGatt is needed for RxBleConnection
                        // BluetoothGatt is produced by RxBleRadioOperationConnect
                        return connectionComponent.rxBleConnection();
                    }
                });
                final Observable<BluetoothGatt> connectedObservable = clientOperationQueue.queue(connectionComponent.connectOperation());
                final Observable<RxBleConnection> disconnectedErrorSingle = connectionComponent.gattCallback().observeDisconnect();
                final Set<ConnectionSubscriptionWatcher> connSubWatchers = connectionComponent.connectionSubscriptionWatchers();

                return Observable.merge(
                        newConnectionObservable.delaySubscription(connectedObservable),
                        disconnectedErrorSingle
                )
                        .doOnSubscribe(new Consumer<Disposable>() {
                            @Override
                            public void accept(Disposable disposable) throws Exception {
                                for (ConnectionSubscriptionWatcher csa : connSubWatchers) {
                                    csa.onConnectionSubscribed();
                                }
                            }
                        })
                        .doFinally(new Action() {
                            @Override
                            public void run() throws Exception {
                                for (ConnectionSubscriptionWatcher csa : connSubWatchers) {
                                    csa.onConnectionUnsubscribed();
                                }
                            }
                        })
                        .subscribeOn(callbacksScheduler)
                        .unsubscribeOn(callbacksScheduler);
            }
        });
    }
}
