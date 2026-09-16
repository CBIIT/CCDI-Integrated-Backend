# Backend API unit-test groups

Unit tests are organized by the backend API testing tickets rather than by the
production package that happens to implement an operation. Shared test doubles
and fixture loaders live in `gov.nih.nci.backendapi.support`.

| Group | Scope | Current test classes |
| --- | --- | --- |
| 01 | HTTP transport and version endpoints | `IndexControllerTest` |
| 02 | GraphQL schema and runtime-wiring contract | `OverviewGraphQLSchemaTest`, `PortalGraphQLSchemaPresenceTest` |
| 03 | Public GraphQL operations | Not started |
| 04 | Global search | `PrivateESDataFetcherGlobalSearchTest` |
| 05 | Participant search and facets | Not started |
| 06 | Participant identifiers and CPI | Not started |
| 07 | Summary and count operations | Not started |
| 08 | Studies and cohort metadata | `PrivateESDataFetcherCohortMetadataTest` |
| 09 | Cohort analytics | `PrivateESDataFetcherCohortChartsTest` |
| 10 | Overview tables | Schema coverage currently resides in group 02; behavioral tests not started |
| 11 | File lookup and export | Supporting query/collector coverage currently resides in groups 12 and 13; endpoint tests not started |
| 12 | Shared OpenSearch query engine | `InventoryESServiceBuildListQueryTest`, `InventoryESServiceAggregationBuildersTest`, `PrivateESDataFetcherHelpersTest`, `ValueUtilsTest` |
| 13 | Shared OpenSearch response handling | `InventoryESServiceHttpMethodsTest`, `InventoryESServiceResponseCollectorsTest` |

The package names begin with `group01_` through `group13_`, keeping the same
order in IDE test explorers and Maven reports. Tests under these packages must
mock OpenSearch and external services. Tests requiring a live service must use
the `*IntegrationTest` or `*IT` naming convention and live under the separate
`gov.nih.nci.integration` package.

Run all unit tests:

```bash
mvn clean test -Dspring.profiles.active=test
```

Run one group, for example group 12:

```bash
mvn -Dtest='gov.nih.nci.backendapi.group12_query_engine.*Test' test
```
