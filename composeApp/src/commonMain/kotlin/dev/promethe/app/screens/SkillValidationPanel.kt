package dev.promethe.app.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.promethe.api.SkillEvaluationCase
import dev.promethe.api.SkillEvaluationSuite
import dev.promethe.app.screens.viewmodel.SkillsUiState
import dev.promethe.app.screens.viewmodel.SkillsViewModel
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

@Composable
internal fun SkillValidationPanel(
    state: SkillsUiState,
    viewModel: SkillsViewModel,
) {
    val skill = state.selectedSkill ?: return
    val validation = state.validation
    var showCases by remember(skill.name) { mutableStateOf(false) }
    var showHistory by remember(skill.name) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(stringResource(Res.string.skills_evaluations), style = MaterialTheme.typography.titleSmall)
        Text(
            if (validation?.canPromote == true) {
                stringResource(Res.string.skills_evaluations_passed)
            } else {
                stringResource(Res.string.skills_evaluations_required)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        validation?.latestRun?.let { run ->
            Text("${run.status} · ${run.results.count { it.status == dev.promethe.api.SkillEvaluationStatus.PASSED }}/${run.results.size}", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showCases = true }, enabled = !state.isSaving && validation != null) { Text(stringResource(Res.string.skills_test_cases)) }
            TextButton(onClick = viewModel::evaluateSkill, enabled = !state.isSaving && !validation?.suites.isNullOrEmpty()) { Text(stringResource(Res.string.skills_run_evaluations)) }
            TextButton(onClick = { showHistory = true }, enabled = validation != null) { Text(stringResource(Res.string.skills_version_history)) }
        }
        Text(stringResource(Res.string.skills_evaluation_cost), style = MaterialTheme.typography.bodySmall)
    }
    if (showCases) {
        var suites by remember { mutableStateOf(validation?.suites?.takeIf { it.isNotEmpty() } ?: listOf(SkillEvaluationSuite("acceptance", listOf(SkillEvaluationCase("case-1", "", ""), SkillEvaluationCase("case-2", "", ""))))) }
        AlertDialog(
            onDismissRequest = { showCases = false },
            title = { Text(stringResource(Res.string.skills_test_cases)) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.skills_test_cases_help))
                    suites.forEachIndexed { suiteIndex, suite ->
                        Text(suite.id, style = MaterialTheme.typography.titleSmall)
                        suite.cases.forEachIndexed { index, case ->
                            OutlinedTextField(value = case.input, onValueChange = { value -> suites = suites.mapIndexed { si, s -> if (si != suiteIndex) s else s.copy(cases = s.cases.mapIndexed { ci, c -> if (ci == index) c.copy(input = value.take(16384)) else c }) } }, label = { Text("${index + 1}. ${stringResource(Res.string.skills_case_input)}") })
                            OutlinedTextField(value = case.expectedOutput, onValueChange = { value -> suites = suites.mapIndexed { si, s -> if (si != suiteIndex) s else s.copy(cases = s.cases.mapIndexed { ci, c -> if (ci == index) c.copy(expectedOutput = value.take(16384)) else c }) } }, label = { Text(stringResource(Res.string.skills_expected_answer)) })
                        }
                        TextButton(onClick = { suites = suites.mapIndexed { si, s -> if (si != suiteIndex) s else s.copy(cases = s.cases + SkillEvaluationCase("case-${s.cases.size + 1}", "", "")) } }, enabled = suites.sumOf { it.cases.size } < 20) { Text(stringResource(Res.string.skills_add_case)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.configureEvaluations(suites)
                    showCases = false
                }, enabled = suites.all { suite -> suite.cases.all { it.input.isNotBlank() && it.expectedOutput.isNotBlank() } && suite.cases.map { it.input.trim() }.distinct().size == suite.cases.size }) { Text(stringResource(Res.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { showCases = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text(stringResource(Res.string.skills_version_history)) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.skills_restore_help))
                    validation?.runs?.forEach { run ->
                        Text("${kotlin.time.Instant.fromEpochMilliseconds(run.startedAt)} · ${run.status}", style = MaterialTheme.typography.labelMedium)
                        run.results.forEach { Text("${it.suiteId}/${it.caseId}: ${it.status}${it.reason?.let { reason -> " — $reason" }.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
                    }
                    validation?.versions?.forEach { version ->
                        Text("${kotlin.time.Instant.fromEpochMilliseconds(version.capturedAt)} · ${version.lifecycle}", style = MaterialTheme.typography.labelMedium)
                        Text(version.content.take(300), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            viewModel.restoreSkill(version.id)
                            showHistory = false
                        }, enabled = !state.isSaving) { Text(stringResource(Res.string.skills_restore_version)) }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistory = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}
