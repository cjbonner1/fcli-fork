/*
 * Copyright 2021-2026 Open Text.
 *
 * The only warranties for products and services of Open Text
 * and its affiliates and licensors ("Open Text") are as may
 * be set forth in the express warranty statements accompanying
 * such products and services. Nothing herein should be construed
 * as constituting an additional warranty. Open Text shall not be
 * liable for technical or editorial errors or omissions contained
 * herein. The information contained herein is subject to change
 * without notice.
 */
package com.fortify.cli.fod._common.scan.helper.dast;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fortify.cli.common.exception.FcliSimpleException;
import com.fortify.cli.common.json.JsonHelper;
import com.fortify.cli.common.progress.helper.IProgressWriterI18n;
import com.fortify.cli.common.rest.unirest.UnexpectedHttpResponseException;
import com.fortify.cli.fod._common.rest.FoDUrls;
import com.fortify.cli.fod._common.scan.helper.FoDScanAssessmentTypeDescriptor;
import com.fortify.cli.fod._common.scan.helper.FoDScanDescriptor;
import com.fortify.cli.fod._common.scan.helper.FoDScanHelper;
import com.fortify.cli.fod._common.scan.helper.FoDScanType;
import com.fortify.cli.fod._common.scan.helper.FoDStartScanResponse;
import com.fortify.cli.fod._common.util.FoDEnums;
import com.fortify.cli.fod.dast_scan.helper.FoDScanConfigDastAutomatedDescriptor;
import com.fortify.cli.fod.release.helper.FoDReleaseDescriptor;

import kong.unirest.HttpResponse;
import kong.unirest.UnirestException;
import kong.unirest.UnirestInstance;
import lombok.Getter;
import lombok.SneakyThrows;

public class FoDScanDastAutomatedHelper extends FoDScanHelper {

    @Getter
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(FoDScanDastAutomatedHelper.class);

    public static final void autoCorrectEntitlementIfNeeded(UnirestInstance unirest,
            FoDReleaseDescriptor releaseDescriptor, FoDScanConfigDastAutomatedDescriptor currentSetup) {
        if (currentSetup == null)
            return;
        String relId = releaseDescriptor.getReleaseId();

        // Find the best available entitlement for the current assessment type
        FoDScanAssessmentTypeDescriptor entitlementToUse = getEntitlementToUse(
                unirest, relId, FoDScanType.Dynamic,
                currentSetup.getAssessmentTypeId().toString(),
                FoDEnums.EntitlementFrequencyType.valueOf(currentSetup.getEntitlementFrequencyType()),
                0); // 0 means find the best one

        if (!Objects.equals(entitlementToUse.getEntitlementId(), currentSetup.getEntitlementId())) {
            LOG.warn("DAST Automated entitlement '{}' for release '{}' is invalid or expired. Auto-correcting to '{}'.",
                    currentSetup.getEntitlementId(), relId, entitlementToUse.getEntitlementId());

            updateSetupWithNewEntitlement(unirest, releaseDescriptor, currentSetup, entitlementToUse);
        }
    }

    private static void updateSetupWithNewEntitlement(UnirestInstance unirest, FoDReleaseDescriptor releaseDescriptor,
            FoDScanConfigDastAutomatedDescriptor currentSetup,
            FoDScanAssessmentTypeDescriptor entitlementToUse) {
        String scanType = currentSetup.getScanType();
        ObjectNode request = (ObjectNode) currentSetup.asObjectNode().deepCopy();

        // Update entitlement fields
        request.put("entitlementId", entitlementToUse.getEntitlementId());
        request.put("entitlementFrequencyType", entitlementToUse.getFrequencyType());

        String endpoint = null;
        if ("Website".equalsIgnoreCase(scanType)) {
            endpoint = "/website-scan-setup";
            JsonNode websiteAssessment = request.get("websiteAssessment");
            if (websiteAssessment != null && websiteAssessment.isObject()) {
                request.setAll((ObjectNode) websiteAssessment);
                request.remove("websiteAssessment");
            }
        } else if ("Workflow".equalsIgnoreCase(scanType)) {
            endpoint = "/workflow-scan-setup";
            JsonNode workflowdrivenAssessment = request.get("workflowdrivenAssessment");
            if (workflowdrivenAssessment != null && workflowdrivenAssessment.isObject()) {
                request.setAll((ObjectNode) workflowdrivenAssessment);
                request.remove("workflowdrivenAssessment");
            }
        }

        if (endpoint != null) {
            // Remove fields that might not be accepted in PUT or cause issues
            request.remove("scanType");
            request.remove("state");
            request.remove("hasUtilizedAdditionalServices");
            request.remove("fileDetails");
            request.remove("apiAssessment");
            request.remove("workflowdrivenAssessment");
            request.remove("websiteAssessment");

            unirest.put(FoDUrls.DAST_AUTOMATED_SCANS + endpoint)
                    .routeParam("relId", releaseDescriptor.getReleaseId())
                    .body(request)
                    .asString().getBody();
            LOG.info("DAST Automated scan setup updated successfully with corrected entitlement.");
        } else {
            LOG.warn("Auto-correction not yet implemented for DAST scan type: '{}'.", scanType);
        }
    }

    public static final FoDScanConfigDastAutomatedDescriptor getSetupDescriptor(UnirestInstance unirest, String relId) {
        // Unlike other scan setup retrievals, DAST Automated scan setup retrieval
        // returns 400
        // when no setup exists, so we need to handle that specifically
        try {
            HttpResponse<JsonNode> response = unirest.get(FoDUrls.DAST_AUTOMATED_SCANS + "/scan-setup")
                    .routeParam("relId", relId)
                    .asObject(JsonNode.class);

            return JsonHelper.treeToValue(response.getBody(), FoDScanConfigDastAutomatedDescriptor.class);
        } catch (UnexpectedHttpResponseException e) {
            if (e.getStatus() == 400) {
                // use the internal FoD "errorCode" to determine if any settings have been
                // defined
                if (e.getMessage().contains("errorCode: 6000")) {
                    return null;
                }
                throw new FcliSimpleException("Bad request. Error: " + e.getMessage());
            }
            throw new FcliSimpleException("Unexpected HTTP error: " + e.getMessage());
        } catch (UnirestException e) {
            throw new FcliSimpleException("Request failed: " + e.getMessage());
        }
    }

    private static boolean isActiveStatus(String status) {
        return "Pending".equals(status) || "Queued".equals(status) || "Waiting".equals(status)
                || "Scheduled".equals(status) || "In_Progress".equals(status);
    }

    @SneakyThrows
    public static final FoDScanDescriptor handleInProgressScan(UnirestInstance unirest,
            FoDReleaseDescriptor releaseDescriptor,
            FoDEnums.InProgressScanActionType inProgressScanActionType,
            IProgressWriterI18n progressWriter, int maxAttempts,
            int waitIntervalSeconds) {
        var releaseId = releaseDescriptor.getReleaseId();
        int waitMillis = waitIntervalSeconds * 1000;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            JsonNode response = unirest.get(FoDUrls.RELEASE_SCANS)
                    .routeParam("relId", releaseId)
                    .queryString("orderBy", "startedDateTime")
                    .queryString("orderByDirection", "DESC")
                    .queryString("fields", "scanId,scanType,analysisStatusType")
                    .asObject(JsonNode.class).getBody();
            JsonNode itemsNode = response.path("items");
            if (!itemsNode.isArray() || itemsNode.isEmpty())
                continue;

            boolean foundActive = false;
            for (JsonNode node : itemsNode) {
                if (!"Dynamic".equals(node.path("scanType").asText()))
                    continue;
                String status = node.path("analysisStatusType").asText();

                if (isActiveStatus(status)) {
                    foundActive = true;
                    FoDScanDescriptor result = handleActiveScan(
                            unirest, releaseId, node, status, releaseDescriptor,
                            inProgressScanActionType, progressWriter, maxAttempts,
                            waitIntervalSeconds, attempt, waitMillis);
                    if (result != null)
                        return result;
                    break; // wait and retry
                }
            }
            // if an existing scan is not running, waiting or in progress, then it's fine to
            // run
            if (!foundActive) {
                progressWriter.writeProgress("Status: Starting new scan");
                return null;
            }
        }
        throw new FcliSimpleException(
                "Unable to start Dynamic scan after " + maxAttempts + " attempts. Please check the UI and try again.");
    }

    public static final FoDScanDescriptor startScan(UnirestInstance unirest, FoDReleaseDescriptor releaseDescriptor) {
        JsonNode response = unirest.post(FoDUrls.DAST_AUTOMATED_SCANS + "/start-scan")
                .routeParam("relId", releaseDescriptor.getReleaseId())
                .asObject(JsonNode.class).getBody();
        FoDStartScanResponse startScanResponse = JsonHelper.treeToValue(response, FoDStartScanResponse.class);
        if (startScanResponse == null || startScanResponse.getScanId() <= 0) {
            throw new FcliSimpleException("Unable to retrieve scan id from response when starting Dynamic scan.");
        }
        JsonNode node = objectMapper.createObjectNode()
                .put("scanId", startScanResponse.getScanId())
                .put("scanType", FoDScanType.Dynamic.name())
                .put("releaseAndScanId",
                        String.format("%s:%s", releaseDescriptor.getReleaseId(), startScanResponse.getScanId()))
                .put("analysisStatusType", "Pending")
                .put("applicationName", releaseDescriptor.getApplicationName())
                .put("releaseName", releaseDescriptor.getReleaseName())
                .put("microserviceName", releaseDescriptor.getMicroserviceName());
        return JsonHelper.treeToValue(node, FoDScanDescriptor.class);
    }

    private static FoDScanDescriptor handleActiveScan(
            UnirestInstance unirest,
            String releaseId,
            JsonNode node,
            String status,
            FoDReleaseDescriptor releaseDescriptor,
            FoDEnums.InProgressScanActionType inProgressScanActionType,
            IProgressWriterI18n progressWriter,
            int maxAttempts,
            int waitIntervalSeconds,
            int attempt,
            int waitMillis) throws InterruptedException {
        String scanId = node.path("scanId").asText();
        switch (inProgressScanActionType) {
            case CancelScanInProgress:
                progressWriter.writeProgress("Status: Cancelling scan %s", scanId);
                FoDScanHelper.cancelScan(unirest, releaseId, scanId);
                break;
            case Queue:
                int timeout = (maxAttempts * waitIntervalSeconds) - (attempt * waitIntervalSeconds);
                progressWriter.writeProgress("Status: Scan %s is %s, waiting up to %s seconds for it to complete",
                        scanId, status, timeout);
                Thread.sleep(waitMillis);
                break;
            case DoNotStartScan:
                progressWriter.writeProgress("Status: A scan is running %s, no new scan will be started", scanId);
                JsonNode scan = objectMapper.createObjectNode()
                        .put("scanId", scanId)
                        .put("scanType", FoDScanType.Dynamic.name())
                        .put("releaseAndScanId", String.format("%s:%s", releaseId, scanId))
                        .put("analysisStatusType", status)
                        .put("applicationName", releaseDescriptor.getApplicationName())
                        .put("releaseName", releaseDescriptor.getReleaseName())
                        .put("microserviceName", releaseDescriptor.getMicroserviceName());
                return JsonHelper.treeToValue(scan, FoDScanDescriptor.class);
        }
        return null;
    }

}
