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

package org.apache.usergrid.corepersistence.io.read;


import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.usergrid.corepersistence.util.CpNamingUtils;
import org.apache.usergrid.persistence.collection.CollectionScope;
import org.apache.usergrid.persistence.collection.EntityCollectionManager;
import org.apache.usergrid.persistence.collection.EntityCollectionManagerFactory;
import org.apache.usergrid.persistence.collection.EntitySet;
import org.apache.usergrid.persistence.collection.MvccEntity;
import org.apache.usergrid.persistence.collection.impl.CollectionScopeImpl;
import org.apache.usergrid.persistence.index.ApplicationEntityIndex;
import org.apache.usergrid.persistence.index.IndexScope;
import org.apache.usergrid.persistence.index.SearchTypes;
import org.apache.usergrid.persistence.index.impl.IndexScopeImpl;
import org.apache.usergrid.persistence.index.query.CandidateResult;
import org.apache.usergrid.persistence.index.query.CandidateResults;
import org.apache.usergrid.persistence.index.query.Query;
import org.apache.usergrid.persistence.model.entity.Entity;
import org.apache.usergrid.persistence.model.entity.Id;

import com.fasterxml.uuid.UUIDComparator;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Func0;
import rx.functions.Func1;


/**
 * Performs a search of the given type along the specified edgeName.  It then loads and validates results,
 * and returns an Observable of SearchResults
 */
@Singleton
public class EntityIndexCommand implements Command<Id, EntityIndexCommand.SearchResults> {

    private final Id applicationId;
    private final ApplicationEntityIndex index;
    private final SearchTypes types;
    private final String query;
    private final int resultSetSize;
    private final String edgeName;
    private final EntityCollectionManagerFactory entityCollectionManagerFactory;


    @Inject
    public EntityIndexCommand( final Id applicationId, final ApplicationEntityIndex index, final SearchTypes types,
                               final String query, final int resultSetSize, final String edgeName,
                               final EntityCollectionManagerFactory entityCollectionManagerFactory ) {
        this.applicationId = applicationId;


        this.index = index;
        this.types = types;
        this.query = query;
        this.resultSetSize = resultSetSize;
        this.edgeName = edgeName;
        this.entityCollectionManagerFactory = entityCollectionManagerFactory;
    }


    @Override
    public Observable<EntityIndexCommand.SearchResults> call( final Observable<Id> idObservable ) {

        //load all the potential candidates
        final Observable<CandidateCollector> collectedCandidates = idObservable.compose( createCandidates() );

        //now we have our collected candidates, load them from ES
        final Observable<SearchResults> loadedEntities = collectedCandidates.map( loadEntities( resultSetSize ) );


        //after we've loaded, remove everything that's left
        loadedEntities.doOnNext( results ->{

            //run the deindex
            Observable.from( results.toRemove ).collect( () -> index.createBatch(), (batch, toRemove) -> {
              batch.deindex( toRemove.indexScope, toRemove.result );
            }).doOnNext( batch -> batch.execute() ).toBlocking().lastOrDefault( null );

        });


        return loadedEntities;
    }




    /**
     * Using an input of observable ids, transform and collect them into a set of CandidateCollector to be loaded, or
     * verified by the EM
     */
    private Observable.Transformer<Id, CandidateCollector> createCandidates() {
        return idObservable -> {

            //create our observable of candidate search results
            final Observable<ScopedCandidateResults> candidateResults = idObservable.flatMap(


                id -> {

                    //create the index scope from the id
                    final IndexScope scope = createScope( id );

                    //perform the ES search of candidate results
                    final Observable<ScopedCandidateResults> scoped = Observable.create(
                        new ElasticSearchObservable( initialSearch( scope ), nextPage( scope ) ) );


                    final Observable<CandidateCollector> collectedResults =  scoped.flatMap( scopedCandidateResults -> {

                        //here b/c the compiler cannot infer type directly
                        final Observable<CandidateResult> candidates =
                            Observable.from( scopedCandidateResults.candidateResults );

                        return candidates.collect( () -> new CandidateCollector( resultSetSize,
                            scopedCandidateResults.candidateResults.getCursor() ), ( collector, candidate ) -> {
                            collector.insert( candidate );
                        } );
                    } );



                    //group our candidates by type
//                   collectedResults.flatMap( collected -> Observable.from( collected.candidates ) ).groupBy( candidate -> candidate.getId().getType() ).co


                    final Observable<SearchResults> resultsObservable = collectedResults.map( collectedCandidates -> {


                        //we can get no results, so quit aggregating if there are none
                        if ( collectedCandidates.candidates.size() == 0 ) {
                            return new SearchResults( 0, collectedCandidates.cursor );
                        }


                        final String collectionType =
                            CpNamingUtils.getCollectionScopeNameFromEntityType( id.getType() );


                        //create our scope to get our entity manager

                        final CollectionScope collectionScope =
                            new CollectionScopeImpl( applicationId, applicationId, collectionType );

                        final EntityCollectionManager ecm =
                            entityCollectionManagerFactory.createCollectionManager( collectionScope );


                        //get our entity ids
                        final List<Id> entityIds =
                            Observable.from( collectedCandidates.candidates ).map( c -> c.getId() ).toList()
                                      .toBlocking().last();

                        //TODO, change this out

                        //an observable of our entity set

                        return ecm.load( entityIds ).map( results -> {

                            Observable.from( entityIds ).collect( () -> new SearchResults( resultSetSize ),
                                ( searchResults, entityIds ) -> {
                                } );
                        } );
                    } );

                    return resultsObservable;
                } );

            return candidateResults;
        };
    }


    /**
     * Perform the initial search with the sourceId
     */
    private Func0<ScopedCandidateResults> initialSearch( final IndexScope scope ) {
        return () -> new ScopedCandidateResults(scope,  index.search(  scope, types, Query.fromQL( query ) ) );
    }


    /**
     * Search the next page for the specified source
     */
    private Func1<String, ScopedCandidateResults> nextPage( final IndexScope scope  ) {
        return cursor -> new ScopedCandidateResults(scope,   index.search( scope, types, Query.fromQL( query ).withCursor(
            cursor ) ) );
    }


    /**
     * Create the scope and return it
     * @param sourceId
     * @return
     */
    private IndexScope createScope(final Id sourceId){
        return new IndexScopeImpl( sourceId, edgeName );
    }

//
//    /**
//     * Function that will load our entities from our candidates, filter stale or missing candidates and return results
//     */
//    private Func1<CandidateCollector, SearchResults> loadEntities( final int expectedSize ) {
//        return candidateCollector -> {
//
//            //from our candidates, group them by id type so we can create scopes
//            Observable.from( candidateCollector.getCandidates() ).groupBy( candidate -> candidate.result.getId().getType() )
//                      .flatMap( groups -> {
//
//
//                          final List<ScopedCandidateResult> candidates = groups.toList().toBlocking().last();
//
//                          //we can get no results, so quit aggregating if there are none
//                          if ( candidates.size() == 0 ) {
//                              return Observable.just( new SearchResults(  0, null ) );
//                          }
//
//
//                          final String typeName = candidates.get( 0 ).result.getId().getType();
//
//                          final String collectionType = CpNamingUtils.getCollectionScopeNameFromEntityType( typeName );
//
//
//                          //create our scope to get our entity manager
//
//                          final CollectionScope scope =
//                              new CollectionScopeImpl( applicationId, applicationId, collectionType );
//
//                          final EntityCollectionManager ecm =
//                              entityCollectionManagerFactory.createCollectionManager( scope );
//
//
//                          //get our entity ids
//                          final List<Id> entityIds =
//                              Observable.from( candidates ).map( c -> c.result.getId() ).toList().toBlocking().last();
//
//                          //TODO, change this out
//
//                          //an observable of our entity set
//
//
//                          //now go through all our candidates and verify
//
//                          return Observable.from( candidates ).collect( () -> new SearchResults(
//                                  expectedSize ),
//                              ( searchResults, candidate ) -> {
//
//                                  final EntitySet entitySet = ecm.load( entityIds ).toBlocking().last();
//
//                                  final MvccEntity entity = entitySet.getEntity( candidate.result.getId() );
//
//
//                                  //our candidate it stale, or target entity was deleted add it to the remove of our
//                                  // collector
//                                  if ( UUIDComparator.staticCompare( entity.getVersion(), candidate.result.getVersion() ) > 0
//                                      || !entity.getEntity().isPresent() ) {
//
//                                      searchResults.addToRemove( candidate  );
//                                      return;
//                                  }
//
//
//                                  searchResults.addEntity( entity.getEntity().get() );
//                              } )
//                              //add the existing set to remove to this set
//                              .doOnNext( results -> results.addToRemove( candidateCollector.getToRemove() ) );
//                      } );
//
//
//            return null;
//        };
//    }


    /**
     * Collects all valid results (in order) as well as candidates to be removed
     */
    public static class CandidateCollector {
        private final List<CandidateResult> candidates;
        private final List<CandidateResult> toRemove;

        private final Map<Id, Integer> indexMapping;

        private final String cursor;


        public CandidateCollector( final int maxSize, final String cursor ) {
            this.cursor = cursor;
            candidates = new ArrayList<>( maxSize );
            toRemove = new ArrayList<>( maxSize );
            indexMapping = new HashMap<>(maxSize);
        }



        public void insert(final CandidateResult newValue){

            final Id candidateId = newValue.getId();

            final Integer index = indexMapping.get( candidateId  );

            if(index == null){
                candidates.add( newValue );
                indexMapping.put( candidateId, candidates.size()-1);
                return;
            }

            //present, perform a comparison

            final CandidateResult existing = candidates.get( index );


            //it's a greater version, add this to ignore
            if(UUIDComparator.staticCompare( existing.getVersion(), newValue.getVersion() ) > 0){
                toRemove.add( newValue );
            }

            //remove the stale version from the list and put it in deindex
            else{
                candidates.remove( index );
                candidates.add( newValue );
                toRemove.add( existing );
            }

        }


        public List<CandidateResult> getCandidates() {
            return candidates;
        }


        public List<CandidateResult> getToRemove() {
            return toRemove;
        }
    }


    public static class SearchResults {
        private final List<Entity> entities;
        private final List<ScopedCandidateResult> toRemove;
        private final int maxSize;

        private final String cursor;


        public SearchResults( final int maxSize, final String cursor ) {
            this.maxSize = maxSize;
            this.cursor = cursor;
            this.entities = new ArrayList<>( maxSize );
            this.toRemove = new ArrayList<>( maxSize );
        }


        public void addEntity( final Entity entity ) {
            this.entities.add( entity );
        }


        public void addToRemove( final Collection<ScopedCandidateResult> stale ) {
            this.toRemove.addAll( stale );
        }


        public void addToRemove( final ScopedCandidateResult candidateResult ) {
            this.toRemove.add( candidateResult );
        }


        public String getCursor() {
            return cursor;
        }


        public boolean isFull(){
            return entities.size() >= maxSize;
        }
    }


    /**
     * An observable that will perform a search and continually emit results while they exist.
     */
    public static class ElasticSearchObservable implements Observable.OnSubscribe<ScopedCandidateResults> {

        private final Func1<String, ScopedCandidateResults> fetchNextPage;
        private final Func0<ScopedCandidateResults> fetchInitialResults;


        public ElasticSearchObservable( final Func0<ScopedCandidateResults> fetchInitialResults,
                                        final Func1<String, ScopedCandidateResults> fetchNextPage ) {
            this.fetchInitialResults = fetchInitialResults;
            this.fetchNextPage = fetchNextPage;
        }


        @Override
        public void call( final Subscriber<? super ScopedCandidateResults> subscriber ) {

            subscriber.onStart();

            try {
                ScopedCandidateResults results = fetchInitialResults.call();


                //emit our next page
                while ( true ) {
                    subscriber.onNext( results );

                    //if we have no cursor, we're done
                    if ( !results.candidateResults.hasCursor() ) {
                        break;
                    }


                    //we have a cursor, get our results to emit for the next page
                    results = fetchNextPage.call( results.candidateResults.getCursor() );
                }

                subscriber.onCompleted();
            }
            catch ( Throwable t ) {
                subscriber.onError( t );
            }
        }
    }


    /**
     * A candidate result, aslong with the scope it was returned in
     */
    public static class ScopedCandidateResult{
        private final IndexScope indexScope;
        private final CandidateResult result;


        public ScopedCandidateResult( final IndexScope indexScope, final CandidateResult result ) {
            this.indexScope = indexScope;
            this.result = result;
        }
    }


    /**
     * Object that represents our candidate results, along with the scope it was searched in
     */
    public static class ScopedCandidateResults {
        private final IndexScope indexScope;
        private CandidateResults candidateResults;


        public ScopedCandidateResults( final IndexScope indexScope, final CandidateResults candidateResults ) {
            this.indexScope = indexScope;
            this.candidateResults = candidateResults;
        }
    }


    /**
     * A message that contains the candidate to keep, and the candidate toRemove and the scope they were searched in
     */
    public static class CandidateGroup {
        private final IndexScope indexScope;
        private final CandidateResult toKeep;
        private final Collection<CandidateResult> toRemove;
        private final String cursor;


        public CandidateGroup( final IndexScope indexScope, final CandidateResult toKeep,
                               final Collection<CandidateResult> toRemove, final String cursor ) {
            this.indexScope = indexScope;
            this.toKeep = toKeep;
            this.toRemove = toRemove;
            this.cursor = cursor;
        }
    }


    /**
     * Compares 2 candidates by version.  The max version is considered greater
     */
    private static final class CandidateVersionComparator {

        public static int compare( final CandidateResult o1, final CandidateResult o2 ) {
            return UUIDComparator.staticCompare( o1.getVersion(), o2.getVersion() );
        }
    }


}
