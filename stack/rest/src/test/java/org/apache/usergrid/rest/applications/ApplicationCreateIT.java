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

package org.apache.usergrid.rest.applications;


import com.fasterxml.jackson.databind.JsonNode;
import com.sun.jersey.api.client.UniformInterfaceException;
import org.apache.usergrid.rest.test.resource2point0.AbstractRestIT;
import org.apache.usergrid.rest.test.resource2point0.endpoints.mgmt.ManagementResponse;
import org.apache.usergrid.rest.test.resource2point0.model.ApiResponse;
import org.apache.usergrid.rest.test.resource2point0.model.Application;
import org.apache.usergrid.rest.test.resource2point0.model.Entity;
import org.apache.usergrid.rest.test.resource2point0.model.Token;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.fail;


public class ApplicationCreateIT extends AbstractRestIT {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationCreateIT.class);


    /**
     * Ensure that we can create many apps and successfully page through them.
     * https://issues.apache.org/jira/browse/USERGRID-491
     *
     * <pre>
     * </pre>
     */
    @Test
    public void testCreateAndRetreieveApps() throws Exception {

        // create app with a collection of "things"

        String orgName = clientSetup.getOrganization().getName();
        String appToDeleteName = clientSetup.getAppName() + "_appToDelete";
        Token orgAdminToken = getAdminToken( clientSetup.getUsername(), clientSetup.getUsername());

        List<Entity> entities = new ArrayList<>();

        UUID appToDeleteId = createAppWithCollection(orgName, appToDeleteName, orgAdminToken, entities);
    }


    private UUID createAppWithCollection(
        String orgName, String appName, Token orgAdminToken, List<Entity> entities) {

        ApiResponse appCreateResponse = clientSetup.getRestClient()
            .management().orgs().organization( orgName ).app().getResource()
            .queryParam( "access_token", orgAdminToken.getAccessToken() )
            .type( MediaType.APPLICATION_JSON )
            .post( ApiResponse.class, new Application( appName ) );
        UUID appId = appCreateResponse.getEntities().get(0).getUuid();

        for ( int i=0; i<10; i++ ) {

            final String entityName = "entity" + i;
            Entity entity = new Entity();
            entity.setProperties(new HashMap<String, Object>() {{
                put("name", entityName );
            }});

            ApiResponse createResponse = clientSetup.getRestClient()
                .org(orgName).app( appName ).collection("things").getResource()
                .queryParam("access_token", orgAdminToken.getAccessToken())
                .type(MediaType.APPLICATION_JSON)
                .post( ApiResponse.class, entity );

            entities.add( createResponse.getEntities().get(0) );
        }
        return appId;
    }
}

