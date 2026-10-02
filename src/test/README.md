# Backend API unit-test areas

Unit tests are organized by descriptive backend API areas rather than ticket
numbers or by the production package that happens to implement an operation.
Shared test doubles and fixture loaders live in
`gov.nih.nci.backendapi.support`.

| Package | Scope | Current test classes |
| --- | --- | --- |
| `transport` | HTTP transport and version endpoints | `IndexControllerTest` |
| `graphqlschema` | GraphQL schema and runtime-wiring contract | `OverviewGraphQLSchemaTest`, `PortalGraphQLSchemaPresenceTest` |
| `publicgraphql` | Public GraphQL operations | Not started |
| `globalsearch` | Global search | `PrivateESDataFetcherGlobalSearchTest` |
| `participantsearch` | Participant search and facets | Not started |
| `participantids` | Participant identifiers and CPI | Not started |
| `summarycounts` | Summary and count operations | Not started |
| `statsbar` | Stats bar counts | `PrivateESDataFetcherStatsBarTest` |
| `studies` | Study details and listings | `PrivateESDataFetcherStudyOperationsTest` |
| `cohortanalyzer` | Cohort manifests, metadata, charts, and survival analytics | `PrivateESDataFetcherCohortManifestTest`, `PrivateESDataFetcherCohortMetadataTest`, `PrivateESDataFetcherCohortChartsTest`, `PrivateESDataFetcherSurvivalAnalyticsTest` |
| `overviewtables` | Overview tables | `PrivateESDataFetcherOverviewTablesTest` |
| `files` | File lookup, cart, and export operations | `PrivateESDataFetcherFileLookupTest`, `InventoryESServiceFileQueryTest` |
| `opensearchquery` | Shared OpenSearch query engine | `InventoryESServiceBuildListQueryTest`, `InventoryESServiceAggregationBuildersTest`, `PrivateESDataFetcherHelpersTest`, `ValueUtilsTest` |
| `opensearchresponse` | Shared OpenSearch response handling | `InventoryESServiceHttpMethodsTest`, `InventoryESServiceResponseCollectorsTest` |

Tests under these packages must mock OpenSearch and external services. Tests
requiring a live service must use the `*IntegrationTest` or `*IT` naming
convention and live under the separate `gov.nih.nci.integration` package.

Run all unit tests:

```bash
mvn clean test -Dspring.profiles.active=test
```

Run one area, for example the OpenSearch query tests:

```bash
mvn -Dtest='gov.nih.nci.backendapi.opensearchquery.*Test' test
```
