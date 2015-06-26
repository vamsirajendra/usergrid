/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.usergrid.tools;


import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;

import org.apache.usergrid.management.OrganizationInfo;
import org.apache.usergrid.persistence.*;
import org.apache.usergrid.persistence.Results.Level;
import org.apache.usergrid.persistence.cassandra.CassandraService;
import org.apache.usergrid.persistence.entities.Application;
import org.apache.usergrid.utils.JsonUtils;
import org.apache.usergrid.utils.StringUtils;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.util.MinimalPrettyPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


/**
 * Export Single application and all entities inside.
 *
 * java -jar usergrid-tools.jar Migration -orgId -appName
 */
public class Migration extends ExportingToolBase {

    static final Logger logger = LoggerFactory.getLogger( Migration.class );
    public static final String APPLICATION_PREFIX = "application";
    private static final String READ_THREAD_COUNT = "readThreads";
    private int readThreadCount;


    /**
     * Represents an Entity r that has been read and is ready for export.
     */
    class ApplicationWriteTask {
        Entity entity;
        Map<String, List<UUID>>          collectionsByName;
        Map<String, List<ConnectionRef>> connectionsByType;
        Map<String, Map<Object, Object>> dictionariesByName;
    }


    /**
     * Export Application entities using multiple threads.
     * <p/>
     * How it works:
     * In main thread we query for IDs of all entities in an application collection, add each ID to read queue.
     * Read-queue workers read entities from cassandra, add data to write queue.
     * One write-queue worker reads data writes to file.
     */
    @Override
    public void runTool(CommandLine line) throws Exception {
        startSpring();

        setVerbose( line );

        applyOrgId( line );
        //Checks for the application name that we want to export.
        applyAppName( line );
        if ( appName == null ){
            logger.error( "Must include a application name using -appName. Aborting..." );
            return;
        }
        prepareBaseOutputFileName( line );
        outputDir = createOutputParentDir();
        logger.info( "Export directory: " + outputDir.getAbsolutePath() );


        //TODO: reset the keyspace to be whatever we specify in the tool
        //if it is not that keyspace then just use the default keyspaces.
        //properties.setProperty( "cassandra.system.keyspace",);
        properties.setProperty( "cassandra.application.keyspace", "Bobgrid_Apps");

        //Then reinitialize the bootstraped cassandra service to use the new properties.
        cass.init();

        //Define how many threads we should use
        if (StringUtils.isNotEmpty( line.getOptionValue( READ_THREAD_COUNT ) )) {
            try {
                readThreadCount = Integer.parseInt( line.getOptionValue( READ_THREAD_COUNT ) );
            } catch (NumberFormatException nfe) {
                logger.error( "-" + READ_THREAD_COUNT + " must be specified as an integer. Aborting..." );
                return;
            }
        } else {
            readThreadCount = 20;
        }

        // start write queue worker

        BlockingQueue<ApplicationWriteTask> writeQueue = new LinkedBlockingQueue<ApplicationWriteTask>();
        ApplicationWriter applicationWriter = new ApplicationWriter( writeQueue );
        Thread writeThread = new Thread( applicationWriter );
        writeThread.start();
        logger.debug( "Write thread started" );

        // start read queue workers

        BlockingQueue<UUID> readQueue = new LinkedBlockingQueue<UUID>();
        List<ApplicationReader> readers = new ArrayList<ApplicationReader>();
        for (int i = 0; i < readThreadCount; i++) {
            ApplicationReader worker = new ApplicationReader( readQueue, writeQueue );
            Thread readerThread = new Thread( worker, "ApplicationReader-" + i );
            readerThread.start();
            readers.add( worker );
        }
        logger.debug( readThreadCount + " read worker threads started" );

        // query for IDs, add each to read queue

        Query query = new Query();
        query.setLimit( MAX_ENTITY_FETCH );
        query.setResultsLevel( Level.IDS );

        //Retrieve application that was given to the exporter
        OrganizationInfo organizationInfo = managementService.getOrganizationByUuid( orgId );
        Application exportedApplication = emf.getApplication( organizationInfo.getName()+"/"+appName  );
        UUID applicationUuid = exportedApplication.getUuid();
        readQueue.add( applicationUuid );
        EntityManager em = emf.getEntityManager( applicationUuid );

        Map<String, Object> metadata = em.getApplicationCollectionMetadata();
        echo( JsonUtils.mapToFormattedJsonString( metadata ) );

        // Loop through the collections. This is the only way to loop
        // through the entities in the application (former namespace).
        for ( String collectionName : metadata.keySet() ) {

            query = new Query();
            query.setLimit( MAX_ENTITY_FETCH );
            query.setResultsLevel( Level.IDS );

            //loop until you get no cursor back.
            Results ids = em.searchCollection( em.getApplicationRef(), collectionName, query );
            while(true) {
                for ( UUID uuid : ids.getIds() ) {
                    //Adds the entities in the collection to the read queue. to the read queue to add the entities later.
                    readQueue.add( uuid );
                    logger.debug( "Added uuid to readQueue: " + uuid );
                }
                if ( ids.getCursor() == null ) {
                    break;
                }

                query.setCursor( ids.getCursor() );

                ids = em.searchCollection( em.getApplicationRef(), collectionName, query );
            }
        }

        applicationWriter.setDone( true );
        for (ApplicationReader aur : readers) {
            aur.setDone( true );
        }

        logger.debug( "Waiting for write thread to complete" );
        writeThread.join();
    }

    public EntityManager applicationEntityManagerCreator() throws Exception{
        OrganizationInfo organizationInfo = managementService.getOrganizationByUuid( orgId );
        UUID applicationUuid = emf.lookupApplication( organizationInfo.getName()+"/"+appName);
        return emf.getEntityManager( applicationUuid );
    }

    @Override
    @SuppressWarnings("static-access")
    public Options createOptions() {

        Options options = super.createOptions();

        Option readThreads = OptionBuilder
                .hasArg().withType(0).withDescription("Read Threads -" + READ_THREAD_COUNT).create(READ_THREAD_COUNT);

        Option appName = OptionBuilder.hasArg().withType( 0 ).withDescription( "Application Name =").create( "appName" );

        options.addOption( readThreads );
        options.addOption( appName );
        return options;
    }


    public class ApplicationReader implements Runnable {

        private boolean done = true;

        private final BlockingQueue<UUID> readQueue;
        private final BlockingQueue<ApplicationWriteTask> writeQueue;

        public ApplicationReader( BlockingQueue<UUID> readQueue, BlockingQueue<ApplicationWriteTask> writeQueue ) {
            this.readQueue = readQueue;
            this.writeQueue = writeQueue;
        }


        @Override
        public void run() {
            try {
                readAndQueueApplicationCollectionEntities();
            } catch (Exception e) {
                logger.error("Error reading data for export", e);
            }
        }


        private void readAndQueueApplicationCollectionEntities() throws Exception {

            EntityManager em = applicationEntityManagerCreator();

            while ( true ) {

                UUID uuid = null;
                try {
                    uuid = readQueue.poll( 30, TimeUnit.SECONDS );
                    logger.debug("Got item from entityId queue: " + uuid );

                    if ( uuid == null && done ) {
                        break;
                    }

                    Entity entity = em.get( uuid );

                    ApplicationWriteTask task = new ApplicationWriteTask();
                    task.entity = entity;

                    addCollectionsToTask(   task, entity );
                    addDictionariesToTask(  task, entity );
                    addConnectionsToTask(   task, entity );

                    writeQueue.add( task );

                } catch ( Exception e ) {
                    logger.error("Error reading data for user " + uuid, e );
                }
            }
        }


        private void addCollectionsToTask(ApplicationWriteTask task, Entity entity) throws Exception {

            EntityManager em = applicationEntityManagerCreator();
            Set<String> collections = em.getCollections( entity );
            if ((collections == null) || collections.isEmpty()) {
                return;
            }

            task.collectionsByName = new HashMap<String, List<UUID>>();

            for (String collectionName : collections) {

                List<UUID> uuids = task.collectionsByName.get( collectionName );
                if ( uuids == null ) {
                    uuids = new ArrayList<UUID>();
                    task.collectionsByName.put( collectionName, uuids );
                }

                Results collectionMembers = em.getCollection( entity, collectionName, null, 100000, Level.IDS, false );

                List<UUID> entityIds = collectionMembers.getIds();

                if ((entityIds != null) && !entityIds.isEmpty()) {
                    for (UUID childEntityUUID : entityIds) {
                        uuids.add( childEntityUUID );
                    }
                }
            }
        }


        private void addDictionariesToTask(ApplicationWriteTask task, Entity entity) throws Exception {
            EntityManager em = applicationEntityManagerCreator();

            Set<String> dictionaries = em.getDictionaries( entity );

            task.dictionariesByName = new HashMap<String, Map<Object, Object>>();

            for (String dictionary : dictionaries) {
                Map<Object, Object> dict = em.getDictionaryAsMap( entity, dictionary );
                task.dictionariesByName.put( dictionary, dict );
            }
        }


        private void addConnectionsToTask(ApplicationWriteTask task, Entity entity) throws Exception {
            EntityManager em = applicationEntityManagerCreator();

            task.connectionsByType = new HashMap<String, List<ConnectionRef>>();

            Set<String> connectionTypes = em.getConnectionTypes( entity );
            for (String connectionType : connectionTypes) {

                List<ConnectionRef> connRefs = task.connectionsByType.get( connectionType );
                if ( connRefs == null ) {
                    connRefs = new ArrayList<ConnectionRef>();
                }

                Results results = em.getConnectedEntities( entity.getUuid(), connectionType, null, Level.IDS );
                List<ConnectionRef> connections = results.getConnections();

                for (ConnectionRef connectionRef : connections) {
                    connRefs.add( connectionRef );
                }
            }
        }

        public void setDone(boolean done) {
            this.done = done;
        }
    }

    class ApplicationWriter implements Runnable {

        private boolean done = false;

        private final BlockingQueue<ApplicationWriteTask> taskQueue;

        public ApplicationWriter( BlockingQueue<ApplicationWriteTask> taskQueue ) {
            this.taskQueue = taskQueue;
        }


        @Override
        public void run() {
            try {
                writeEntities();
            } catch (Exception e) {
                logger.error("Error writing export data", e);
            }
        }


        private void writeEntities() throws Exception {
            //TODO: change this to take in the application name.
            EntityManager em = applicationEntityManagerCreator();

            //TODO: could do one file for collections and one file for applications or multiple.
            // write one JSON file for management application users
            JsonGenerator usersFile =
                    getJsonGenerator( createOutputFile( APPLICATION_PREFIX, em.getApplication().getName() ) );
            usersFile.setPrettyPrinter( new MinimalPrettyPrinter( "" ) );
            //usersFile.writeStartArray();

            int count = 0;

            while ( true ) {

                try {
                    ApplicationWriteTask task = taskQueue.poll( 30, TimeUnit.SECONDS );
                    if ( task == null && done ) {
                        break;
                    }

                    // write user to application file
                    //TODO: GREY make a method that writes the object as a whole entity , with properties and all
                    usersFile.writeStartObject();

                    saveEntity(usersFile,task);
                    echo( task.entity );

                    // write metadata to metadata file
                    saveConnections(   usersFile, task );
                    saveDictionaries(  usersFile, task );

                    usersFile.writeEndObject();
                    //usersFile.writeRaw( '}' );
                    usersFile.writeRaw( '\n' );
                    logger.debug( "Exported user {}", task.entity.getProperty( "email" ) );

                    count++;
                    if ( count % 1000 == 0 ) {
                        logger.info("Exported {} entities", count);
                    }


                } catch (InterruptedException e) {
                    throw new Exception("Interrupted", e);
                }
            }

           // usersFile.writeEndArray();
            usersFile.close();

            logger.info("Exported TOTAL {} entities", count);
        }

        private void saveDictionaries( JsonGenerator jg, ApplicationWriteTask task ) throws Exception {

            jg.writeFieldName( "dictionaries" );
            jg.writeStartObject();

            for (String dictionary : task.dictionariesByName.keySet() ) {

                Map<Object, Object> dict = task.dictionariesByName.get( dictionary );

                if (dict.isEmpty()) {
                    continue;
                }

                jg.writeFieldName( dictionary );

                jg.writeStartObject();

                for (Map.Entry<Object, Object> entry : dict.entrySet()) {
                    jg.writeFieldName( entry.getKey().toString() );
                    jg.writeObject( entry.getValue() );
                }

                jg.writeEndObject();
            }
            jg.writeEndObject();
        }

        private void saveEntity(JsonGenerator jg, ApplicationWriteTask task) throws Exception {
            jg.writeFieldName( "entity" );
            jg.writeObject( task.entity );
        }

        private void saveConnections( JsonGenerator jg, ApplicationWriteTask task ) throws Exception {

            jg.writeFieldName( "connections" );
            jg.writeStartObject();

            for (String connectionType : task.connectionsByType.keySet() ) {

                jg.writeFieldName( connectionType );
                jg.writeStartArray();

                List<ConnectionRef> connections = task.connectionsByType.get( connectionType );
                for (ConnectionRef connectionRef : connections) {
                    jg.writeObject( connectionRef.getConnectedEntity().getUuid() );
                }

                jg.writeEndArray();
            }
            jg.writeEndObject();
        }

        public void setDone(boolean done) {
            this.done = done;
        }
    }
}

