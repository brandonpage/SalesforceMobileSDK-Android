/*
 * Copyright (c) 2026-present, salesforce.com, inc.
 * All rights reserved.
 *
 * Vector DB spike Phase 4: on-device RAG sample.
 */
package com.salesforce.samples.restexplorer.rag

/**
 * Tiny corpus of Salesforce-flavoured help snippets that the RAG demo
 * indexes on first use. Short, self-contained paragraphs chosen so that
 * a 1-2 sentence query can be unambiguously grounded against a single
 * document \u2014 gives the demo a clean \u201clookup a specific fact\u201d feel
 * even with a very small LLM.
 *
 * Content here is paraphrased from publicly available Salesforce help
 * topics; nothing is confidential or source-linked. Tests and the demo
 * both consume this same list, so adding or editing an entry changes
 * both places.
 */
object RagSampleCorpus {

    /** One indexable document. */
    data class Doc(
        val title: String,
        val text: String
    )

    /**
     * Canonical ordering is stable: tests and perf numbers reference
     * zero-based indices in this list.
     */
    val docs: List<Doc> = listOf(
        Doc(
            title = "Create a Lead",
            text = "A Lead in Salesforce represents a prospect who has expressed interest but has not yet been qualified. Create a Lead from the Leads tab by clicking New, entering the prospect's name, company, and contact details, then saving. Converting a qualified Lead produces an Account, a Contact, and optionally an Opportunity."
        ),
        Doc(
            title = "Opportunity Stages",
            text = "Opportunity Stages track the progression of a sales deal from Prospecting through Closed Won or Closed Lost. Each stage has an associated probability used by the forecast. Administrators configure stages in Setup under Opportunity Stage; the system tracks stage history automatically in OpportunityHistory."
        ),
        Doc(
            title = "Custom Objects",
            text = "A custom object is a database table you define to store data unique to your organization. Custom objects support custom fields, validation rules, workflows, sharing rules, page layouts, and reports. Create one in Setup under Object Manager by clicking Create then Custom Object. API names end in two underscores and a c."
        ),
        Doc(
            title = "Validation Rules",
            text = "Validation rules verify that data entered by a user meets your business standards before a record is saved. Each rule contains a formula that evaluates to TRUE or FALSE; when the formula returns TRUE the save is blocked and the configured error message is displayed. Rules run on insert and update but not on delete."
        ),
        Doc(
            title = "Workflow Rules",
            text = "A Workflow Rule automates standard actions\u2014such as field updates, email alerts, tasks, and outbound messages\u2014when records meet specified criteria. Workflow runs on record save. For more complex flows, including multi-step branches or user input, Salesforce now recommends using Flow Builder instead of creating new Workflow Rules."
        ),
        Doc(
            title = "Approval Processes",
            text = "An Approval Process defines the steps required to approve a record, such as a high-value Opportunity or a time-off Request. Each process specifies entry criteria, the approver hierarchy, field edits during approval, and final actions on approval or rejection. Users submit a record for approval using the Submit for Approval button."
        ),
        Doc(
            title = "Permission Sets",
            text = "A Permission Set is a collection of settings and permissions that extends a user's functional access beyond what the user's profile grants. Permission Sets can grant object access, field access, tab visibility, system permissions, and apex class access. They are additive and cannot reduce permissions granted by a profile."
        ),
        Doc(
            title = "Sharing Rules",
            text = "Sharing Rules grant additional record-level access to groups of users based on record ownership or field criteria. Use them to open up access when the organization-wide default is Private or Public Read Only. Rules apply only to records they match; they never take access away."
        ),
        Doc(
            title = "Record Types",
            text = "Record Types let you offer different business processes, picklist values, and page layouts to different users for the same object. Each Record Type is associated with one business process, one or more profiles, and a page layout. Users pick a Record Type when creating a new record."
        ),
        Doc(
            title = "Platform Events",
            text = "Platform Events deliver secure, scalable, custom notifications within Salesforce or from external sources using an event-driven architecture. Publishers raise events using Apex, Flow, REST, or SOAP; subscribers receive events in Apex triggers, Flows, or CometD clients. Event messages are retained for up to 72 hours."
        ),
        Doc(
            title = "External Services",
            text = "External Services lets declarative users integrate with external REST APIs without writing code. You register an OpenAPI or Interagent-compliant schema in Setup, then invoke the imported actions from Flow Builder. Authentication is provided by a Named Credential; responses are automatically mapped into Apex-defined types."
        ),
        Doc(
            title = "Lightning Web Components",
            text = "Lightning Web Components (LWC) are a modern framework for building fast, reactive UI components on the Salesforce platform. LWC uses standard web-platform features like Web Components, ES modules, and custom elements; a thin Salesforce-specific layer provides base components, wire adapters for data, and the Lightning Data Service cache."
        ),
        Doc(
            title = "Apex Triggers",
            text = "An Apex Trigger executes custom code before or after DML events such as insert, update, delete, and undelete. Triggers run in bulk; Salesforce best practice is one trigger per object that delegates to a handler class so logic is testable and the 150-statement DML limit is respected. Triggers cannot call system callouts synchronously."
        ),
        Doc(
            title = "Governor Limits",
            text = "Governor Limits are per-transaction resource limits enforced by the Salesforce multi-tenant platform. They cap SOQL queries (100), DML statements (150), heap size (6 MB sync / 12 MB async), CPU time (10 s sync / 60 s async), callouts (100) and queue-ables chained (50). Hitting a limit aborts the whole transaction with an uncatchable exception."
        ),
        Doc(
            title = "Data Loader",
            text = "Data Loader is a client application for bulk importing, exporting, updating, and deleting Salesforce records. It uses the Bulk API for large volumes and the SOAP API for smaller ones; mappings are stored in SDL files and can be reused. Data Loader runs on Windows and macOS, and exposes both an interactive UI and a command-line interface."
        )
    )
}
