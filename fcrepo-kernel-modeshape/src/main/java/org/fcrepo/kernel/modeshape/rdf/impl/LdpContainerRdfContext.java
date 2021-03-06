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
package org.fcrepo.kernel.modeshape.rdf.impl;

import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Resource;

import org.fcrepo.kernel.api.models.FedoraResource;
import org.fcrepo.kernel.api.identifiers.IdentifierConverter;
import org.fcrepo.kernel.modeshape.rdf.converters.ValueConverter;
import org.fcrepo.kernel.modeshape.rdf.impl.mappings.PropertyValueIterator;
import org.fcrepo.kernel.modeshape.utils.UncheckedFunction;
import org.fcrepo.kernel.modeshape.utils.UncheckedPredicate;

import java.util.stream.Stream;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;

import static java.util.stream.Stream.empty;
import static java.util.stream.Stream.of;
import static org.apache.jena.graph.NodeFactory.createURI;
import static org.apache.jena.graph.Triple.create;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;
import static org.fcrepo.kernel.api.FedoraTypes.LDP_BASIC_CONTAINER;
import static org.fcrepo.kernel.api.FedoraTypes.LDP_DIRECT_CONTAINER;
import static org.fcrepo.kernel.api.FedoraTypes.LDP_HAS_MEMBER_RELATION;
import static org.fcrepo.kernel.api.FedoraTypes.LDP_INDIRECT_CONTAINER;
import static org.fcrepo.kernel.api.FedoraTypes.LDP_INSERTED_CONTENT_RELATION;
import static org.fcrepo.kernel.api.FedoraTypes.LDP_MEMBER_RESOURCE;
import static org.fcrepo.kernel.api.RdfLexicon.LDP_MEMBER;
import static org.fcrepo.kernel.api.RdfLexicon.MEMBER_SUBJECT;
import static org.fcrepo.kernel.modeshape.identifiers.NodeResourceConverter.nodeConverter;
import static org.fcrepo.kernel.modeshape.rdf.converters.PropertyConverter.getPropertyNameFromPredicate;
import static org.fcrepo.kernel.modeshape.utils.FedoraTypesUtils.getJcrNode;
import static org.fcrepo.kernel.modeshape.utils.FedoraTypesUtils.getReferencePropertyName;
import static org.fcrepo.kernel.modeshape.utils.StreamUtils.iteratorToStream;
import static org.fcrepo.kernel.modeshape.utils.UncheckedFunction.uncheck;

/**
 * @author cabeer
 * @author ajs6f
 * @since 9/25/14
 */
public class LdpContainerRdfContext extends NodeRdfContext {

    /**
     * Default constructor.
     *
     * @param resource the resource
     * @param idTranslator the id translator
     * @throws javax.jcr.RepositoryException if repository exception occurred
     */
    public LdpContainerRdfContext(final FedoraResource resource,
                                  final IdentifierConverter<Resource, FedoraResource> idTranslator)
            throws RepositoryException {
        super(resource, idTranslator);

        concat(getMembershipContext(resource)
                .flatMap(uncheck(p -> memberRelations(nodeConverter.convert(p.getParent())))));
    }

    @SuppressWarnings("unchecked")
    private static Stream<Property> getMembershipContext(final FedoraResource resource) throws RepositoryException {
        return iteratorToStream(getJcrNode(resource).getReferences(LDP_MEMBER_RESOURCE))
                    .filter(UncheckedPredicate.uncheck((final Property p) -> {
                        final Node container = p.getParent();
                        return container.isNodeType(LDP_DIRECT_CONTAINER)
                            || container.isNodeType(LDP_INDIRECT_CONTAINER);
                    }));
    }

    /**
     * Get the member relations assert on the subject by the given node
     * @param container with relations
     * @return Stream of triples
     * @throws RepositoryException on error
     */
    private Stream<Triple> memberRelations(final FedoraResource container) throws RepositoryException {
        final org.apache.jena.graph.Node memberRelation;

        if (container.hasProperty(LDP_HAS_MEMBER_RELATION)) {
            final Property property = getJcrNode(container).getProperty(LDP_HAS_MEMBER_RELATION);
            memberRelation = createURI(property.getString());
        } else if (container.hasType(LDP_BASIC_CONTAINER)) {
            memberRelation = LDP_MEMBER.asNode();
        } else {
            return empty();
        }

        final String insertedContainerProperty;

        if (container.hasType(LDP_INDIRECT_CONTAINER)) {
            if (container.hasProperty(LDP_INSERTED_CONTENT_RELATION)) {
                insertedContainerProperty = getJcrNode(container).getProperty(LDP_INSERTED_CONTENT_RELATION)
                    .getString();
            } else {
                return empty();
            }
        } else {
            insertedContainerProperty = MEMBER_SUBJECT.getURI();
        }

        return container.getChildren().flatMap(
            UncheckedFunction.uncheck(child -> {
                final org.apache.jena.graph.Node childSubject = uriFor(child.getDescribedResource());

                if (insertedContainerProperty.equals(MEMBER_SUBJECT.getURI())) {
                    return of(create(subject(), memberRelation, childSubject));
                }
                String insertedContentProperty = getPropertyNameFromPredicate(getJcrNode(resource()),
                        createResource(insertedContainerProperty), null);

                if (child.hasProperty(insertedContentProperty)) {
                    // do nothing, insertedContentProperty is good

                } else if (child.hasProperty(getReferencePropertyName(insertedContentProperty))) {
                    // The insertedContentProperty is a pseudo reference property
                    insertedContentProperty = getReferencePropertyName(insertedContentProperty);

                } else {
                    // No property found!
                    return empty();
                }

                return iteratorToStream(new PropertyValueIterator(
                        getJcrNode(child).getProperty(insertedContentProperty)))
                    .map(uncheck(v -> create(subject(), memberRelation,
                        new ValueConverter(getJcrNode(container).getSession(), translator()).convert(v).asNode())));
            }));
    }
}
