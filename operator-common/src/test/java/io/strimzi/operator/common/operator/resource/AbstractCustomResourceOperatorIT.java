/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.Doneable;
import io.fabric8.kubernetes.api.model.apiextensions.CustomResourceDefinition;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.api.kafka.model.status.ConditionBuilder;
import io.strimzi.operator.KubernetesVersion;
import io.strimzi.operator.PlatformFeaturesAvailability;
import io.strimzi.test.k8s.KubeClusterResource;
import io.strimzi.test.k8s.cluster.KubeCluster;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicReference;

import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The main purpose of the Integration Tests for the operators is to test them against a real Kubernetes cluster.
 * Real Kubernetes cluster has often some quirks such as some fields being immutable, some fields in the spec section
 * being created by the Kubernetes API etc. These things are hard to test with mocks. These IT tests make it easy to
 * test them against real clusters.
 */
@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractCustomResourceOperatorIT<C extends KubernetesClient, T extends CustomResource, L extends CustomResourceList<T>, D extends Doneable<T>> {
    protected static final Logger log = LogManager.getLogger(AbstractCustomResourceOperatorIT.class);
    public static final String RESOURCE_NAME = "my-test-resource";
    protected static Vertx vertx;
    protected static KubernetesClient client;
    protected static String namespace = "custom-resource-operator-it-namespace";

    protected static final Condition readyCondition = new ConditionBuilder()
            .withType("Ready")
            .withStatus("True")
            .build();

    private static KubeClusterResource cluster;

    @BeforeAll
    public void before() {
        cluster = KubeClusterResource.getInstance();
        cluster.setTestNamespace(namespace);

        assertDoesNotThrow(() -> KubeCluster.bootstrap(), "Could not bootstrap server");
        vertx = Vertx.vertx();
        client = new DefaultKubernetesClient();

        if (cluster.getTestNamespace() != null && System.getenv("SKIP_TEARDOWN") == null) {
            log.warn("Namespace {} is already created, going to delete it", namespace);
            kubeClient().deleteNamespace(namespace);
            cmdKubeClient().waitForResourceDeletion("Namespace", namespace);
        }

        log.info("Creating namespace: {}", namespace);
        kubeClient().createNamespace(namespace);
        cmdKubeClient().waitForResourceCreation("Namespace", namespace);

        log.info("Creating CRD");
        client.customResourceDefinitions().createOrReplace(getCrd());
        log.info("Created CRD");
    }

    @AfterAll
    public void after() {
        if (vertx != null) {
            vertx.close();
        }
        if (kubeClient().getNamespace(namespace) != null && System.getenv("SKIP_TEARDOWN") == null) {
            log.warn("Deleting namespace {} after tests run", namespace);
            kubeClient().deleteNamespace(namespace);
            cmdKubeClient().waitForResourceDeletion("Namespace", namespace);
        }
    }

    abstract CrdOperator<C, T, L, D> operator();
    abstract CustomResourceDefinition getCrd();
    abstract T getResource();
    abstract T getResourceWithModifications(T resourceInCluster);
    abstract T getResourceWithNewReadyStatus(T resourceInCluster);
    abstract void assertReady(VertxTestContext context, T modifiedCustomResource);

    @Test
    public void testUpdateStatus(VertxTestContext context) {
        Checkpoint async = context.checkpoint();
        CrdOperator<C, T, L, D> op = operator();

        log.info("Getting Kubernetes version");
        PlatformFeaturesAvailability.create(vertx, client)
                .setHandler(context.succeeding(pfa -> context.verify(() -> {
                    assertThat("Kubernetes version : " + pfa.getKubernetesVersion() + " is too old",
                            pfa.getKubernetesVersion().compareTo(KubernetesVersion.V1_11), CoreMatchers.is(not(lessThan(0))));
                })))

                .compose(pfa -> {
                    log.info("Creating resource");
                    return op.reconcile(namespace, RESOURCE_NAME, getResource());
                })
                .setHandler(context.succeeding())
                .compose(rrCreated -> {
                    T newStatus = getResourceWithNewReadyStatus(rrCreated.resource());

                    log.info("Updating resource status");
                    return op.updateStatusAsync(newStatus);
                })
                .setHandler(context.succeeding())

                .compose(rrModified -> op.getAsync(namespace, RESOURCE_NAME))
                .setHandler(context.succeeding(modifiedCustomResource -> context.verify(() -> {
                    assertReady(context, modifiedCustomResource);
                })))

                .compose(rrModified -> {
                    log.info("Deleting resource");
                    return op.reconcile(namespace, RESOURCE_NAME, null);
                })
                .setHandler(context.succeeding(rrDeleted ->  async.flag()));
    }


    /**
     * Tests what happens when the resource is deleted while updating the status
     *
     * @param context
     */
    @Test
    public void testUpdateStatusAfterResourceDeletedThrowsKubernetesClientException(VertxTestContext context) {
        Checkpoint async = context.checkpoint();

        CrdOperator<C, T, L, D> op = operator();

        AtomicReference<T> newStatus = new AtomicReference<>();

        log.info("Getting Kubernetes version");
        PlatformFeaturesAvailability.create(vertx, client)
                .setHandler(context.succeeding(pfa -> context.verify(() -> {
                    assertThat("Kubernetes version : " + pfa.getKubernetesVersion() + " is too old",
                            pfa.getKubernetesVersion().compareTo(KubernetesVersion.V1_11), CoreMatchers.is(not(lessThan(0))));
                })))
                .compose(pfa -> {
                    log.info("Creating resource");
                    return op.reconcile(namespace, RESOURCE_NAME, getResource());
                })
                .setHandler(context.succeeding())

                .compose(rr -> {
                    log.info("Saving resource with status change prior to deletion");
                    newStatus.set(getResourceWithNewReadyStatus(op.get(namespace, RESOURCE_NAME)));
                    log.info("Deleting resource");
                    return op.reconcile(namespace, RESOURCE_NAME, null);
                })
                .setHandler(context.succeeding())

                .compose(rrDeleted -> {
                    log.info("Updating resource with new status - should fail");
                    return op.updateStatusAsync(newStatus.get());
                })
                .setHandler(context.failing(e -> context.verify(() -> {
                    assertThat(e, instanceOf(KubernetesClientException.class));
                    async.flag();
                })));
    }

    /**
     * Tests what happens when the resource is modifed while updating the status
     *
     * @param context
     */
    @Test
    public void testUpdateStatusAfterResourceUpdatedThrowsKubernetesClientException(VertxTestContext context) {
        Checkpoint async = context.checkpoint();

        CrdOperator<C, T, L, D> op = operator();

        Promise updateFailed = Promise.promise();

        log.info("Getting Kubernetes version");
        PlatformFeaturesAvailability.create(vertx, client)
                .setHandler(context.succeeding(pfa -> context.verify(() -> {
                    assertThat("Kubernetes version : " + pfa.getKubernetesVersion() + " is too old",
                            pfa.getKubernetesVersion().compareTo(KubernetesVersion.V1_11), CoreMatchers.is(not(lessThan(0))));
                })))
                .compose(pfa -> {
                    log.info("Creating resource");
                    return op.reconcile(namespace, RESOURCE_NAME, getResource());
                })
                .setHandler(context.succeeding())
                .compose(rrCreated -> {
                    T updated = getResourceWithModifications(rrCreated.resource());
                    T newStatus = getResourceWithNewReadyStatus(rrCreated.resource());

                    log.info("Updating resource (mocking an update due to some other reason)");
                    op.operation().inNamespace(namespace).withName(RESOURCE_NAME).patch(updated);

                    log.info("Updating resource status after underlying resource has changed");
                    return op.updateStatusAsync(newStatus);
                })
                .setHandler(context.failing(e -> context.verify(() -> {
                    assertThat("Exception was not KubernetesClientException, it was : " + e.toString(),
                            e, instanceOf(KubernetesClientException.class));
                    updateFailed.complete();
                })));

        updateFailed.future().compose(v -> {
            log.info("Deleting resource");
            return op.reconcile(namespace, RESOURCE_NAME, null);
        })
        .setHandler(context.succeeding(v -> async.flag()));
    }
}

