/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.usergrid.corepersistence.rx.impl;


import rx.Observable;
import rx.functions.Action2;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.internal.operators.OperatorScan;


/**
 * An operation for performing a collect until the shortCircuitWhen returns true
 */
public class CollectUntil<T, R> implements Observable.Transformer<T, R> {

    final Func0<R> stateFactory;
    final Action2<R, ? super T> collector;
    final Func1<R, Boolean> shortCircuitWhen;


    public CollectUntil(  final Func0<R> stateFactory,final Func1<R, Boolean> shortCircuitWhen,  final Action2<R, ? super T> collector) {
        this.stateFactory = stateFactory;
        this.collector = collector;
        this.shortCircuitWhen = shortCircuitWhen;
    }


    @Override
    public Observable<R> call( final Observable<T> tObservable ) {
        Func2<R, T, R> accumulator = ( state, value ) -> {
            collector.call( state, value );
            return state;
        };


        return tObservable.lift( new OperatorScan<>( stateFactory, accumulator ) ).takeUntil( shortCircuitWhen );
    }
}

