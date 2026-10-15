package com.gymcoach.app.ui.gyms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gymcoach.app.R
import com.gymcoach.app.ui.more.ChoiceChips
import com.gymcoach.app.ui.more.MiniStepper
import com.gymcoach.app.ui.more.MultiChips
import com.gymcoach.app.ui.more.SwitchRow
import com.gymcoach.core.knowledge.KnowledgeBase
import com.gymcoach.core.model.EquipmentCategory
import com.gymcoach.core.model.EquipmentDef
import com.gymcoach.core.model.GymItem
import com.gymcoach.core.model.GymSet
import com.gymcoach.core.model.ParamSpec
import com.gymcoach.core.units.Units
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** The eight gym colour dots (GymSet.colorIndex 0..7). */
val GymColors: List<Color> = listOf(
    Color(0xFF2E7D32), Color(0xFF1565C0), Color(0xFFEF6C00), Color(0xFF6A1B9A),
    Color(0xFFC62828), Color(0xFF00838F), Color(0xFF4E342E), Color(0xFF546E7A),
)

@Composable
fun categoryTitle(category: EquipmentCategory): String = when (category) {
    EquipmentCategory.FREE_WEIGHTS -> stringResource(R.string.su_cat_free_weights)
    EquipmentCategory.RACKS_BENCHES -> stringResource(R.string.su_cat_racks_benches)
    EquipmentCategory.CABLES -> stringResource(R.string.su_cat_cables)
    EquipmentCategory.MACHINES_UPPER -> stringResource(R.string.su_cat_machines_upper)
    EquipmentCategory.MACHINES_LOWER -> stringResource(R.string.su_cat_machines_lower)
    EquipmentCategory.BODYWEIGHT_STATIONS -> stringResource(R.string.su_cat_bodyweight)
    EquipmentCategory.CARDIO -> stringResource(R.string.su_cat_cardio)
    EquipmentCategory.ACCESSORIES -> stringResource(R.string.su_cat_accessories)
}

/**
 * Eight collapsible category sections inside the editor's LazyColumn. A category auto-expands while a search is active.
 * Header = "Free weights 6/8" (selected / total in the catalogue, session flags excluded).
 */
fun LazyListScope.equipmentSections(
    knowledge: KnowledgeBase,
    visible: List<EquipmentDef>,
    gym: GymSet,
    expanded: Set<EquipmentCategory>,
    searching: Boolean,
    onToggleCategory: (EquipmentCategory) -> Unit,
    onToggleItem: (String) -> Unit,
    onParam: (String, String, JsonElement) -> Unit,
    onInfo: (EquipmentDef) -> Unit,
    onAddToday: (String) -> Unit,
) {
    val chosen = gym.items.associateBy { it.equipmentId }
    for (category in EquipmentCategory.values()) {
        val inCategory = visible.filter { it.category == category }
        if (inCategory.isEmpty()) continue
        val all = knowledge.equipment.values.filter { it.category == category && !it.sessionFlag }
        val selectedCount = all.count { it.id in chosen }
        val open = searching || category in expanded
        item(key = "cat-" + category.name) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleCategory(category) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${categoryTitle(category)}  $selectedCount/${all.size}", style = MaterialTheme.typography.titleMedium)
                Icon(imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            HorizontalDivider()
        }
        if (open) {
            itemsOf(inCategory, key = { "eq-" + it.id }) { def ->
                EquipmentRow(def, chosen[def.id], onToggleItem, onParam, onInfo, onAddToday)
            }
        }
    }
}

private fun <T> LazyListScope.itemsOf(list: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    items(count = list.size, key = { index -> key(list[index]) }) { index -> content(list[index]) }
}

@Composable
private fun EquipmentRow(
    def: EquipmentDef,
    item: GymItem?,
    onToggleItem: (String) -> Unit,
    onParam: (String, String, JsonElement) -> Unit,
    onInfo: (EquipmentDef) -> Unit,
    onAddToday: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item != null, onCheckedChange = { onToggleItem(def.id) })
            Text(def.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).clickable { onToggleItem(def.id) })
            if (item == null) {
                TextButton(onClick = { onAddToday(def.id) }) { Text(stringResource(R.string.gym_today_only)) }
            }
            IconButton(onClick = { onInfo(def) }) { Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.gym_info, def.name)) }
        }
        if (item != null && def.params.isNotEmpty()) {
            Column(modifier = Modifier.padding(start = 48.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (spec in def.params) ParamControl(def.id, spec, item, onParam)
            }
        }
        HorizontalDivider()
    }
}

private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.let { if (it.isString) null else it.doubleOrNull }
private fun JsonElement?.asBool(): Boolean? = (this as? JsonPrimitive)?.let { if (it.isString) null else it.booleanOrNull }
private fun JsonElement?.asNumbers(): List<Double>? = (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
private fun JsonElement?.asStrings(): List<String>? = (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }

private fun humanKey(key: String): String = key.replace('_', ' ')

private fun stepFor(equipmentId: String, spec: ParamSpec): Double = when {
    equipmentId == "dumbbells" && spec.key == "max_kg" -> 2.0
    spec.key.endsWith("_kg") && (spec.max ?: 0.0) > 40.0 -> 2.5
    else -> 0.5
}

/** One catalogue parameter (R4 §6.1): booleans as switches, numbers as steppers or chips, lists as chips or text. */
@Composable
private fun ParamControl(equipmentId: String, spec: ParamSpec, item: GymItem, onParam: (String, String, JsonElement) -> Unit) {
    val stored = item.params[spec.key]
    when (spec.type) {
        "boolean" -> SwitchRow(humanKey(spec.key), stored.asBool() ?: spec.default.asBool() ?: false, { onParam(equipmentId, spec.key, JsonPrimitive(it)) })
        "number", "integer" -> NumberParam(equipmentId, spec, item, onParam)
        "number_list" -> NumberListParam(equipmentId, spec, stored.asNumbers() ?: spec.default.asNumbers() ?: emptyList(), onParam)
        else -> StringListParam(equipmentId, spec, stored.asStrings() ?: spec.default.asStrings() ?: emptyList(), onParam)
    }
}

@Composable
private fun NumberParam(equipmentId: String, spec: ParamSpec, item: GymItem, onParam: (String, String, JsonElement) -> Unit) {
    val current = item.params[spec.key].asDouble() ?: spec.default.asDouble()
    val unit = spec.unit ?: ""
    val label = humanKey(spec.key) + if (unit.isEmpty()) "" else " ($unit)"
    val chips: List<Double>? = when {
        equipmentId == "dumbbells" && spec.key == "step_kg" -> listOf(1.0, 2.0, 2.5)
        equipmentId == "plates" && spec.key == "smallest_plate_kg" -> listOf(1.25, 2.5, 5.0)
        spec.key == "stack_step_kg" -> listOf(5.0, 2.5)
        else -> null
    }
    when {
        spec.key == "half_step_kg" -> {
            val stack = item.params["stack_step_kg"].asDouble() ?: 5.0
            SwitchRow(stringResource(R.string.gym_half_step), current != null, { on -> onParam(equipmentId, spec.key, if (on) JsonPrimitive(stack / 2.0) else JsonNull) })
        }
        chips != null -> {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            ChoiceChips(chips, current, { Units.trim(it) }) { onParam(equipmentId, spec.key, JsonPrimitive(it)) }
        }
        else -> {
            val step = stepFor(equipmentId, spec)
            val lo = spec.min ?: 0.0
            val hi = spec.max ?: Double.MAX_VALUE
            val base = current ?: lo
            MiniStepper(
                label = label,
                value = if (current == null) "–" else Units.trim(current),
                onMinus = { onParam(equipmentId, spec.key, JsonPrimitive((base - step).coerceIn(lo, hi))) },
                onPlus = { onParam(equipmentId, spec.key, JsonPrimitive((base + step).coerceIn(lo, hi))) },
            )
        }
    }
}

private val KettlebellChoices: List<Double> = listOf(4.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 24.0, 28.0, 32.0, 40.0)

@Composable
private fun NumberListParam(equipmentId: String, spec: ParamSpec, current: List<Double>, onParam: (String, String, JsonElement) -> Unit) {
    val label = humanKey(spec.key) + (spec.unit?.let { " ($it)" } ?: "")
    Text(label, style = MaterialTheme.typography.bodyMedium)
    if (equipmentId == "kettlebells") {
        MultiChips(KettlebellChoices, current.toSet(), { Units.trim(it) }) { v ->
            val next = if (v in current) current - v else (current + v).sorted()
            onParam(equipmentId, spec.key, JsonArray(next.map { JsonPrimitive(it) }))
        }
    } else {
        var text by remember(current) { mutableStateOf(current.joinToString(", ") { Units.trim(it) }) }
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                val parsed = t.split(',', ';').mapNotNull { it.trim().replace(',', '.').toDoubleOrNull() }.sorted()
                onParam(equipmentId, spec.key, JsonArray(parsed.map { JsonPrimitive(it) }))
            },
            label = { Text(stringResource(R.string.gym_list_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StringListParam(equipmentId: String, spec: ParamSpec, current: List<String>, onParam: (String, String, JsonElement) -> Unit) {
    var text by remember(current) { mutableStateOf(current.joinToString(", ")) }
    Text(humanKey(spec.key), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            val parsed = t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            onParam(equipmentId, spec.key, JsonArray(parsed.map { JsonPrimitive(it) }))
        },
        label = { Text(stringResource(R.string.gym_list_hint_text)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
