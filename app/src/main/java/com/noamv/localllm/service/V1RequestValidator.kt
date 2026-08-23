package com.noamv.localllm.service

import com.noamv.localllm.contract.InsightContract
import com.noamv.localllm.contract.InsightRequest

internal object V1RequestValidator {
    fun errorMessage(request: InsightRequest): String? = when {
        request.contractVersion > InsightContract.VERSION ->
            "Client asked for contract v${request.contractVersion}; " +
                "this service implements v${InsightContract.VERSION}."
        request.contractVersion < 1 ->
            "Contract v${request.contractVersion} is not supported."
        request.resultSchema != null ->
            "Structured result schemas are not implemented by contract v1."
        else -> null
    }
}
