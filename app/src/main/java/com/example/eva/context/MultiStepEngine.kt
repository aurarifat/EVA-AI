package com.example.eva.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StepItem(
    val stepNumber: Int,
    val totalSteps: Int,
    val commandText: String,
    val status: StepStatus = StepStatus.PENDING,
    val result: String? = null
)

enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class MultiStepPlan(
    val id: String,
    val originalQuery: String,
    val steps: List<StepItem>,
    val currentStepIndex: Int = 0,
    val isPaused: Boolean = false,
    val isCancelled: Boolean = false
)

class MultiStepEngine {

    private val _currentPlan = MutableStateFlow<MultiStepPlan?>(null)
    val currentPlan: StateFlow<MultiStepPlan?> = _currentPlan.asStateFlow()

    /**
     * Splits a compound command string into sequential steps.
     * E.g. "Open YouTube, search for Class 9 Physics, then set media volume to 50 percent"
     */
    fun parseSequentialCommand(query: String): List<String> {
        val splitRegex = Regex("""\s*(?:,\s*(?:then|and\s+then)?|\b(?:then|and\s+then|after\s+that|also)\b|;)\s*""", RegexOption.IGNORE_CASE)
        val segments = query.split(splitRegex)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return if (segments.size > 1) segments else emptyList()
    }

    fun createPlan(originalQuery: String, stepsText: List<String>): MultiStepPlan {
        val total = stepsText.size
        val steps = stepsText.mapIndexed { index, text ->
            StepItem(
                stepNumber = index + 1,
                totalSteps = total,
                commandText = text
            )
        }
        val plan = MultiStepPlan(
            id = "plan_${System.currentTimeMillis()}",
            originalQuery = originalQuery,
            steps = steps,
            currentStepIndex = 0
        )
        _currentPlan.value = plan
        return plan
    }

    fun updateStepStatus(stepIndex: Int, status: StepStatus, result: String? = null) {
        val plan = _currentPlan.value ?: return
        val updatedSteps = plan.steps.toMutableList()
        if (stepIndex in updatedSteps.indices) {
            updatedSteps[stepIndex] = updatedSteps[stepIndex].copy(status = status, result = result)
            _currentPlan.value = plan.copy(
                steps = updatedSteps,
                currentStepIndex = if (status == StepStatus.COMPLETED && stepIndex + 1 < updatedSteps.size) stepIndex + 1 else plan.currentStepIndex
            )
        }
    }

    fun cancelPlan() {
        val plan = _currentPlan.value ?: return
        _currentPlan.value = plan.copy(isCancelled = true)
    }

    fun clearPlan() {
        _currentPlan.value = null
    }
}
