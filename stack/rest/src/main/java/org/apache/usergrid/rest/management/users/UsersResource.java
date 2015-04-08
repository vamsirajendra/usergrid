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
package org.apache.usergrid.rest.management.users;


import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.usergrid.management.exceptions.ManagementException;
import org.apache.usergrid.management.export.ExportService;
import org.apache.usergrid.rest.RootResource;
import org.apache.usergrid.rest.applications.ServiceResource;
import org.apache.usergrid.rest.security.annotations.RequireSystemAccess;
import org.apache.usergrid.rest.utils.JSONPUtils;
import org.apache.usergrid.services.exceptions.ServiceResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.apache.amber.oauth2.common.exception.OAuthSystemException;
import org.apache.amber.oauth2.common.message.OAuthResponse;

import org.apache.usergrid.management.UserInfo;
import org.apache.usergrid.rest.AbstractContextResource;
import org.apache.usergrid.rest.ApiResponse;
import org.apache.usergrid.rest.exceptions.AuthErrorInfo;
import org.apache.usergrid.rest.exceptions.RedirectionException;
import org.apache.usergrid.security.shiro.utils.SubjectUtils;

import com.sun.jersey.api.json.JSONWithPadding;
import com.sun.jersey.api.view.Viewable;

import net.tanesha.recaptcha.ReCaptchaImpl;
import net.tanesha.recaptcha.ReCaptchaResponse;

import static javax.servlet.http.HttpServletResponse.SC_ACCEPTED;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.usergrid.rest.exceptions.SecurityException.mappableSecurityException;


@Component( "org.apache.usergrid.rest.management.users.UsersResource" )
@Produces( {
        MediaType.APPLICATION_JSON, "application/javascript", "application/x-javascript", "text/ecmascript",
        "application/ecmascript", "text/jscript"
} )
public class UsersResource extends AbstractContextResource {

    private static final Logger logger = LoggerFactory.getLogger( UsersResource.class );

    @Autowired
    protected ExportService exportService;
    String errorMsg;
    UserInfo user;


    public UsersResource() {
        logger.info( "ManagementUsersResource initialized" );
    }


    @Path(RootResource.USER_ID_PATH)
    public UserResource getUserById( @Context UriInfo ui, @PathParam( "userId" ) String userIdStr ) throws Exception {

        return getUserResource(management.getAdminUserByUuid(UUID.fromString(userIdStr)), "user id", userIdStr);
    }


    @Path( "{username}" )
    public UserResource getUserByUsername( @Context UriInfo ui, @PathParam( "username" ) String username )
            throws Exception {

        if ( "me".equals( username ) ) {
            UserInfo user = SubjectUtils.getAdminUser();
            if ( ( user != null ) && ( user.getUuid() != null ) ) {
                return getSubResource( UserResource.class ).init( management.getAdminUserByUuid( user.getUuid() ) );
            }
            throw mappableSecurityException( "unauthorized", "No admin identity for access credentials provided" );
        }

        return getUserResource(management.getAdminUserByUsername(username), "username", username);
    }

    private UserResource getUserResource(UserInfo user, String type, String value) throws ManagementException {
        if (user == null) {
            throw new ManagementException("Could not find organization for " + type + " : " + value);
        }
        return getSubResource(UserResource.class).init(user);
    }


    @Path(RootResource.EMAIL_PATH)
    public UserResource getUserByEmail( @Context UriInfo ui, @PathParam( "email" ) String email ) throws Exception {

        return getUserResource(management.getAdminUserByEmail(email), "email", email);
    }


    @POST
    @Consumes( MediaType.APPLICATION_FORM_URLENCODED )
    public JSONWithPadding createUser( @Context UriInfo ui, @FormParam( "username" ) String username,
                                       @FormParam( "name" ) String name, @FormParam( "email" ) String email,
                                       @FormParam( "password" ) String password,
                                       @QueryParam( "callback" ) @DefaultValue( "callback" ) String callback )
            throws Exception {

        logger.info( "Create user: " + username );

        ApiResponse response = createApiResponse();
        response.setAction( "create user" );

        UserInfo user = management.createAdminUser( username, name, email, password, false, false );
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if ( user != null ) {
            result.put( "user", user );
            response.setData( result );
            response.setSuccess();
        }
        else {
            throw mappableSecurityException( AuthErrorInfo.BAD_CREDENTIALS_SYNTAX_ERROR );
        }

        return new JSONWithPadding( response, callback );
    }

	/*
     * @POST
	 * 
	 * @Consumes(MediaType.MULTIPART_FORM_DATA) public JSONWithPadding
	 * createUserFromMultipart(@Context UriInfo ui,
	 * 
	 * @FormDataParam("username") String username,
	 * 
	 * @FormDataParam("name") String name,
	 * 
	 * @FormDataParam("email") String email,
	 * 
	 * @FormDataParam("password") String password) throws Exception {
	 * 
	 * return createUser(ui, username, name, email, password); }
	 */


    @GET
    @Path( "resetpw" )
    @Produces( MediaType.TEXT_HTML )
    public Viewable showPasswordResetForm( @Context UriInfo ui ) {
        return handleViewable( "resetpw_email_form", this );
    }


    @POST
    @Path( "resetpw" )
    @Consumes( "application/x-www-form-urlencoded" )
    @Produces( MediaType.TEXT_HTML )
    public Viewable handlePasswordResetForm( @Context UriInfo ui, @FormParam( "email" ) String email,
                                             @FormParam( "recaptcha_challenge_field" ) String challenge,
                                             @FormParam( "recaptcha_response_field" ) String uresponse ) {

        try {
            if ( isBlank( email ) ) {
                errorMsg = "No email provided, try again...";
                return handleViewable( "resetpw_email_form", this );
            }

            //we don't require recaptcha - only use it if it is present in the props file
            boolean reCaptchaPassed = false;
            if ( useReCaptcha() ) {

                ReCaptchaImpl reCaptcha = new ReCaptchaImpl();
                reCaptcha.setPrivateKey(properties.getRecaptchaPrivate());

                ReCaptchaResponse reCaptchaResponse =
                        reCaptcha.checkAnswer(httpServletRequest.getRemoteAddr(), challenge, uresponse);

                if (reCaptchaResponse.isValid()) {
                    reCaptchaPassed = true;
                }
            } else {
                reCaptchaPassed = true;
            }

            if (reCaptchaPassed) {
                user = management.findAdminUser(email);
                if (user != null) {
                    management.startAdminUserPasswordResetFlow(user);
                    return handleViewable("resetpw_email_success", this);
                } else {
                    errorMsg = "We don't recognize that email, try again...";
                    return handleViewable("resetpw_email_form", this);
                }
            } else {
                errorMsg = "Incorrect Captcha, try again...";
                return handleViewable("resetpw_email_form", this);
            }
            
        }
        catch ( RedirectionException e ) {
            throw e;
        }
        catch ( Exception e ) {
            return handleViewable( "error", e );
        }
    }

    @POST
    @Path( "export" )
    @Consumes(APPLICATION_JSON)
    @RequireSystemAccess
    public Response exportPostJson( @Context UriInfo ui,Map<String, Object> json,
                                    @QueryParam("callback") @DefaultValue("") String callback )
        throws OAuthSystemException {

        UUID jobUUID = null;
        Map<String, String> uuidRet = new HashMap<String, String>();

        Map<String,Object> properties;
        Map<String, Object> storage_info;

        try {
            //checkJsonExportProperties(json);
            if((properties = ( Map<String, Object> )  json.get( "properties" )) == null){
                throw new NullPointerException("Could not find 'properties'");
            }
            storage_info = ( Map<String, Object> ) properties.get( "storage_info" );
            String storage_provider = ( String ) properties.get( "storage_provider" );
            if(storage_provider == null) {
                throw new NullPointerException( "Could not find field 'storage_provider'" );
            }
            if(storage_info == null) {
                throw new NullPointerException( "Could not find field 'storage_info'" );
            }


            String bucketName = ( String ) storage_info.get( "bucket_location" );
            String accessId = ( String ) storage_info.get( "s3_access_id" );
            String secretKey = ( String ) storage_info.get( "s3_key" );

            if(bucketName == null) {
                throw new NullPointerException( "Could not find field 'bucketName'" );
            }
            if(accessId == null) {
                throw new NullPointerException( "Could not find field 's3_access_id'" );
            }
            if(secretKey == null) {
                throw new NullPointerException( "Could not find field 's3_key'" );
            }


            jobUUID = exportService.schedule( json );
            uuidRet.put( "Export Entity", jobUUID.toString() );
        }
        catch ( NullPointerException e ) {
            return Response.status( SC_BAD_REQUEST ).type( JSONPUtils.jsonMediaType( callback ) )
                           .entity( ServiceResource.wrapWithCallback( e.getMessage(), callback ) ).build();
        }
        catch ( Exception e ) {
            //TODO:throw descriptive error message and or include on in the response
            //TODO:fix below, it doesn't work if there is an exception. Make it look like the OauthResponse.
            OAuthResponse errorMsg =
                    OAuthResponse.errorResponse( SC_INTERNAL_SERVER_ERROR ).setErrorDescription( e.getMessage() )
                                 .buildJSONMessage();
            return Response.status( errorMsg.getResponseStatus() ).type( JSONPUtils.jsonMediaType( callback ) )
                           .entity( ServiceResource.wrapWithCallback( errorMsg.getBody(), callback ) ).build();
        }

        return Response.status( SC_ACCEPTED ).entity( uuidRet ).build();
    }


    public String getErrorMsg() {
        return errorMsg;
    }


    public UserInfo getUser() {
        return user;
    }
}
