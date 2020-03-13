/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.client.CustomResource;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.KafkaConnectList;
import io.strimzi.api.kafka.model.DoneableKafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectBuilder;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * The main purpose of the Integration Tests for the operators is to test them against a real Kubernetes cluster.
 * Real Kubernetes cluster has often some quirks such as some fields being immutable, some fields in the spec section
 * being created by the Kubernetes API etc. These things are hard to test with mocks. These IT tests make it easy to
 * test them against real clusters.
 */
@ExtendWith(VertxExtension.class)
public class KafkaConnectCrdOperatorIT extends AbstractCustomResourceOperatorIT {
    protected static final Logger log = LogManager.getLogger(KafkaConnectCrdOperatorIT.class);

    @Override
    protected CrdOperator operator() {
        return new CrdOperator(vertx, client, KafkaConnect.class, KafkaConnectList.class, DoneableKafkaConnect.class);
    }

    @Override
    protected CustomResourceDefinition getCrd() {
        return Crds.kafkaConnect();
    }

    @Override
    protected String getNamespace() {
        return "kafka-connect-crd-it-namespace";
    }

    @Override
    protected KafkaConnect getResource() {
        return new KafkaConnectBuilder()
                .withApiVersion(KafkaConnect.RESOURCE_GROUP + "/" + KafkaConnect.V1BETA1)
                .withNewMetadata()
                    .withName(RESOURCE_NAME)
                    .withNamespace(getNamespace())
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .withNewStatus()
                .endStatus()
                .build();
    }

    @Override
    protected CustomResource getResourceWithModifications(CustomResource resourceInCluster) {
        return new KafkaConnectBuilder((KafkaConnect) resourceInCluster)
                .editSpec()
                    .withNewLivenessProbe()
                        .withInitialDelaySeconds(14)
                    .endLivenessProbe()
                .endSpec()
                .build();
    }

    @Override
    protected CustomResource getResourceWithNewReadyStatus(CustomResource resourceInCluster) {
        return new KafkaConnectBuilder((KafkaConnect) resourceInCluster)
                .withNewStatus()
                    .withConditions(READY_CONDITION)
                .endStatus()
                .build();
    }

    @Override
    protected void assertReady(VertxTestContext context, CustomResource modifiedCustomResource) {
        KafkaConnect kafkaConnect = (KafkaConnect) modifiedCustomResource;
        context.verify(() -> assertThat(kafkaConnect.getStatus()
                .getConditions()
                .get(0), is(READY_CONDITION)));
    }
}