/*
 * Licensed to DuraSpace under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.
 *
 * DuraSpace licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.transform.http;

import static com.google.common.collect.ImmutableMap.of;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.ok;
import static org.apache.jena.riot.WebContent.contentTypeN3;
import static org.apache.jena.riot.WebContent.contentTypeNTriples;
import static org.apache.jena.riot.WebContent.contentTypeRDFXML;
import static org.apache.jena.riot.WebContent.contentTypeResultsBIO;
import static org.apache.jena.riot.WebContent.contentTypeResultsJSON;
import static org.apache.jena.riot.WebContent.contentTypeResultsXML;
import static org.apache.jena.riot.WebContent.contentTypeSPARQLQuery;
import static org.apache.jena.riot.WebContent.contentTypeSSE;
import static org.apache.jena.riot.WebContent.contentTypeTextCSV;
import static org.apache.jena.riot.WebContent.contentTypeTextPlain;
import static org.apache.jena.riot.WebContent.contentTypeTextTSV;
import static org.apache.jena.riot.WebContent.contentTypeTurtle;
import static org.fcrepo.transform.transformations.LDPathTransform.APPLICATION_RDF_LDPATH;
import static org.fcrepo.transform.transformations.LDPathTransform.CONFIGURATION_FOLDER;
import static org.fcrepo.transform.transformations.LDPathTransform.getResourceTransform;
import static org.fcrepo.transform.transformations.LDPathTransform.DEFAULT_TRANSFORM_RESOURCE;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.fcrepo.http.api.ContentExposingResource;
import org.fcrepo.kernel.api.exception.InvalidChecksumException;
import org.fcrepo.kernel.api.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.api.models.FedoraBinary;
import org.fcrepo.kernel.api.models.FedoraResource;
import org.fcrepo.transform.TransformationFactory;
import org.jvnet.hk2.annotations.Optional;
import org.slf4j.Logger;
import org.springframework.context.annotation.Scope;

import com.codahale.metrics.annotation.Timed;
import com.google.common.annotations.VisibleForTesting;

/**
 * Endpoint for transforming object properties using stored
 * or POSTed transformations.
 *
 * @author cbeer
 */
@Scope("request")
@Path("/{path: .*}/fcr:transform")
public class FedoraTransform extends ContentExposingResource {

    @Inject
    protected Session session;

    private static final Logger LOGGER = getLogger(FedoraTransform.class);

    @Inject
    @Optional
    private TransformationFactory transformationFactory;

    @PathParam("path") protected String externalPath;

    /**
     * Default entry point
     */
    public FedoraTransform() { }

    /**
     * Create a new FedoraNodes instance for a given path
     * @param externalPath the external path
     */
    @VisibleForTesting
    public FedoraTransform(final String externalPath) {
        this.externalPath = externalPath;
    }


    /**
     * Register the LDPath configuration tree in JCR
     *
     * @throws RepositoryException if repository exception occurred
     * @throws java.io.IOException if IO exception occurred
     */
    @PostConstruct
    public void setUpRepositoryConfiguration() throws RepositoryException, IOException {

        final Session internalSession = sessions.getInternalSession();
        try {

            // Create this resource or it becomes a PairTree which is not referenceable.
            containerService.findOrCreate(internalSession, "/fedora:system/fedora:transform");

            final Map<String, String> transformations = of(
                    "default", "/ldpath/default/ldpath_program.txt",
                    "deluxe", "/ldpath/deluxe/ldpath_program.txt");
            transformations.forEach((key, value) -> {

                final FedoraResource resource =
                        containerService.findOrCreate(internalSession, CONFIGURATION_FOLDER + key);
                LOGGER.debug("Transformation default resource: {}", resource.getPath());

                final Stream<FedoraResource> children = resource.getChildren();
                children.forEach(child -> LOGGER.debug("Child is {}", child.getPath()));
                final String uploadPath = CONFIGURATION_FOLDER + key + "/" + DEFAULT_TRANSFORM_RESOURCE;
                if (!resource.getChildren().anyMatch(child -> child.getPath().equalsIgnoreCase(uploadPath))) {
                    LOGGER.debug("Uploading the stream to {}", uploadPath);
                    final FedoraBinary base = binaryService.findOrCreate(internalSession, uploadPath);
                    try {
                        base.setContent(getClass().getResourceAsStream(value), null, null, null, null);
                    } catch (final InvalidChecksumException e) {
                        throw new RepositoryRuntimeException(e);
                    }
                }
            });

            internalSession.save();
        } finally {
            internalSession.logout();
        }
    }

    /**
     * Execute an LDpath program transform
     *
     * @param program the LDpath program
     * @return Binary blob
     * @throws RepositoryException if repository exception occurred
     */
    @GET
    @Path("{program}")
    @Produces({APPLICATION_JSON})
    @Timed
    public Response evaluateLdpathProgram(@PathParam("program") final String program)
            throws RepositoryException {
        LOGGER.info("GET transform, '{}', for '{}'", program, externalPath);

        return ok()
            .entity(getResourceTransform(resource(), session, nodeService, program).apply(getResourceTriples()))
            .header("Warning", "The fcr:transform endpoint is deprecated and will be removed" +
                    "in a future version of Fedora")
            .build();

    }

    /**
     * Get the LDPath output as a JSON stream appropriate for e.g. Solr
     *
     * @param contentType the content type
     * @param requestBodyStream the request body stream
     * @return LDPath as a JSON stream
     */
    @POST
    @Consumes({APPLICATION_RDF_LDPATH, contentTypeSPARQLQuery})
    @Produces({APPLICATION_JSON, contentTypeTextTSV, contentTypeTextCSV,
            contentTypeSSE, contentTypeTextPlain, contentTypeResultsJSON,
            contentTypeResultsXML, contentTypeResultsBIO, contentTypeTurtle,
            contentTypeN3, contentTypeNTriples, contentTypeRDFXML})
    @Timed
    public Response evaluateTransform(@HeaderParam("Content-Type") final MediaType contentType,
                                    final InputStream requestBodyStream) {

        if (transformationFactory == null) {
            transformationFactory = new TransformationFactory();
        }
        LOGGER.info("POST transform for '{}'", externalPath);

        return ok()
            .entity(transformationFactory.getTransform(contentType, requestBodyStream).apply(getResourceTriples()))
            .header("Warning", "The fcr:transform endpoint is deprecated and will be removed" +
                    "in a future version of Fedora")
            .build();

    }

    @Override
    protected Session session() {
        return session;
    }

    @Override
    protected String externalPath() {
        return externalPath;
    }

    @Override
    protected void addResourceHttpHeaders(final FedoraResource resource) {
        throw new UnsupportedOperationException();
    }
}
