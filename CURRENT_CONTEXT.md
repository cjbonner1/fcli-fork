# Current Project Context

This document summarizes the current state of the `fortify-fcli-tool-fork` project.

## Objective
The primary objective is to enhance the NCD (Number of Contributing Developers) report to provide more accurate and configurable insights into active developers for billing and entitlement purposes.

## Problem Statement
The NCD report currently misidentifies developers who have left the company as "active" due to a hardcoded 90-day commit look-back period. This leads to inaccurate billing.

## Changes Implemented (on `feature/ncd-report-config` branch)

The following modifications have been made to address the problem:

1.  **`fcli-core/fcli-license/src/main/java/com/fortify/cli/license/ncd_report/config/NcdReportConfig.java`**:
    *   The `commitPeriod` field was introduced to allow configuration of the commit look-back period (e.g., "30d", "60d", "1y").
    *   A setter method (`setCommitPeriod`) was added for this field.
    *   The default `commitPeriod` has been set to "60d" based on user feedback.

2.  **`fcli-core/fcli-license/src/main/java/com/fortify/cli/license/ncd_report/collector/NcdReportContext.java`**:
    *   A `commitOffsetDateTime` field was added to store the dynamically calculated commit cutoff date.
    *   The constructor was updated to accept `commitOffsetDateTime`.
    *   A getter (`getCommitOffsetDateTime`) was added for this field.

3.  **`fcli-core/fcli-license/src/main/java/com/fortify/cli/license/ncd_report/cli/cmd/NcdReportCreateCommand.java`**:
    *   New command-line options `--commit-period` (e.g., "30d") and `--commit-since` (e.g., "yyyy-MM-dd") were added for direct control over the commit look-back.
    *   The `createReportContext` method was updated to calculate the `commitOffsetDateTime` based on these options (prioritizing command-line over config file) and pass it to the `NcdReportContext`.

4.  **`fcli-core/fcli-license/src/main/java/com/fortify/cli/license/ncd_report/generator/{gitlab,github,ado}/NcdReport*ResultsGenerator.java`**:
    *   The `generateCommitDataForBranches` methods in these files were updated to retrieve the `commitOffsetDateTime` from `reportContext().getCommitOffsetDateTime()` instead of `reportContext().reportConfig().getCommitOffsetDateTime()`.

## How to Use New Options

To control the commit look-back period for the NCD report:

*   **Via `NcdReportConfig.yml` (default 60 days):**
    ```yaml
    commitPeriod: 30d # Example: 30 days
    ```
*   **Via Command-Line Options (override config):**
    *   `--commit-period`:
        ```bash
        fcli license ncd-report create --config NcdReportConfig.yml --commit-period 30d
        ```
    *   `--commit-since`:
        ```bash
        fcli license ncd-report create --config NcdReportConfig.yml --commit-since 2023-01-01
        ```

## Outstanding Tasks

1.  **Java Version Compatibility:** The project currently fails to build with Java 25.0.2 due to an incompatibility with the Kotlin compiler used by Gradle. **Action Required:** Switch your local Java environment to an LTS version like Java 17 or Java 21.
2.  **Activity Profile Report (Pending):** We discussed creating a more detailed "Activity Profile" report that categorizes developers by their last commit date (e.g., <30 days, 30-90 days, >90 days). This is currently a pending task.
3.  **Running the NCD Report:** Once the Java version is resolved and the project builds successfully, the next step is to run the NCD report to observe the impact of the implemented changes.

## Next Steps

1.  **Resolve Java Version:** Please switch your Java version to 17 or 21.
2.  **Build Project:** Run `./gradlew build`.
3.  **Run NCD Report:** Execute the NCD report with the new options to verify the output.