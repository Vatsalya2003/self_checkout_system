package com.selfcheckout.self_checkout.messaging;

// MESSAGING library (Week 6). Every name on the broker, in one place, so a
// publisher and its consumer can never disagree about a queue name.
//
// Week 6: services no longer call each other over HTTP. Every piece of
// cross-service communication is a message through RabbitMQ:
//
//   catalog.requests        queue     checkout/inventory/analytics -> catalog   CatalogRequested
//   catalog.snapshots       fanout    catalog -> everyone listening             CatalogPublished
//   inventory.stock-commit  queue     checkout -> inventory                     StockCommitRequested
//   checkout.stock-replies  queue     inventory -> checkout                     StockCommitted / StockCommitRejected
//   analytics.scans         queue     checkout -> analytics                     ScansRecorded
//
// Queues are published to through RabbitMQ's default exchange ("") with the
// queue name as the routing key -- the simplest point-to-point route. Only the
// catalog snapshot is a broadcast, because three services want the same copy.
//
// Each queue has a dead-letter queue (<name>.dlq). A message lands there only
// if it can never be processed (it cannot even be read as JSON); transient
// failures are retried instead. Anything in a DLQ is counted and logged
// (DeadLetterMonitor), never silent.
public final class Topology {

    private Topology() {}

    public static final String CATALOG_REQUESTS = "catalog.requests";
    public static final String CATALOG_SNAPSHOTS = "catalog.snapshots";
    public static final String STOCK_COMMIT_REQUESTS = "inventory.stock-commit";
    public static final String STOCK_COMMIT_REPLIES = "checkout.stock-replies";
    public static final String SCANS = "analytics.scans";

    /** Where rejected messages go; routed to <queue>.dlq by the queue's own name. */
    public static final String DEAD_LETTERS = "dead-letters";

    /** The durable point-to-point queues (each one gets a .dlq). */
    public static final String[] QUEUES = {CATALOG_REQUESTS, STOCK_COMMIT_REQUESTS, STOCK_COMMIT_REPLIES, SCANS};

    public static String dlq(String queue) {
        return queue + ".dlq";
    }
}
