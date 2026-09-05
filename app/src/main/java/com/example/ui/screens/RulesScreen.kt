package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.FilterRule
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardFilterMode
import com.example.data.model.MatchType
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Indigo400
import com.example.ui.theme.Rose400
import com.example.ui.theme.Sky400
import com.example.ui.theme.Slate300
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun RulesScreen(
    rules: List<FilterRule>,
    config: ForwardConfig,
    onUpdateConfig: (ForwardConfig) -> Unit,
    onAddRule: (pattern: String, type: MatchType, label: String, keyword: String) -> Unit,
    onUpdateRule: (FilterRule) -> Unit,
    onToggleRule: (FilterRule, Boolean) -> Unit,
    onDeleteRule: (FilterRule) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<FilterRule?>(null) }

    val filteredRules = rules.filter { rule ->
        searchQuery.isBlank() ||
                rule.senderPattern.contains(searchQuery, ignoreCase = true) ||
                rule.label.contains(searchQuery, ignoreCase = true) ||
                rule.keywordFilter.contains(searchQuery, ignoreCase = true)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Mode Selector Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate900)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Slate800, RoundedCornerShape(18.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "حالت فیلتر پیامک‌های ارسالی",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "نحوه انتخاب پیامک‌ها برای ارسال به سرور را تعیین کنید.",
                            fontSize = 12.sp,
                            color = Slate400,
                            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                        )

                        // Option 1: Specific Rules
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (config.filterMode == ForwardFilterMode.SPECIFIC_RULES_ONLY) Slate800 else Slate950)
                                .clickable {
                                    onUpdateConfig(config.copy(filterMode = ForwardFilterMode.SPECIFIC_RULES_ONLY))
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = config.filterMode == ForwardFilterMode.SPECIFIC_RULES_ONLY,
                                onClick = {
                                    onUpdateConfig(config.copy(filterMode = ForwardFilterMode.SPECIFIC_RULES_ONLY))
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Sky400,
                                    unselectedColor = Slate600
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "فقط شماره‌های مشخص (لیست قوانین)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "تنها پیامک‌های دریافتی از شماره‌های منطبق با قوانین زیر فوروارد می‌شوند.",
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Option 2: Forward All
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (config.filterMode == ForwardFilterMode.ALL_MESSAGES) Slate800 else Slate950)
                                .clickable {
                                    onUpdateConfig(config.copy(filterMode = ForwardFilterMode.ALL_MESSAGES))
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = config.filterMode == ForwardFilterMode.ALL_MESSAGES,
                                onClick = {
                                    onUpdateConfig(config.copy(filterMode = ForwardFilterMode.ALL_MESSAGES))
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Sky400,
                                    unselectedColor = Slate600
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "ارسال تمام پیامک‌های دریافتی",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "تمامی پیامک‌ها بدون در نظر گرفتن فرستنده به سرور فرستاده می‌شوند.",
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                        }
                    }
                }
            }

            // Search Bar & Add Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "قوانین شماره‌های مجاز (${rules.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Sky400, contentColor = Slate950),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("add_rule_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("افزودن قانون", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("جستجو بر اساس شماره، برچسب یا کلیدواژه...", color = Slate400) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Slate400)
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Sky400,
                        unfocusedBorderColor = Slate800,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Slate900,
                        unfocusedContainerColor = Slate900
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (filteredRules.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Slate900)
                            .border(1.dp, Slate800, RoundedCornerShape(16.dp))
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = null,
                                tint = Slate600,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (rules.isEmpty()) "هنوز قانونی ثبت نشده است" else "قانونی با این مشخصات یافت نشد",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Slate400
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "برای انتقال پیامک‌ها از فرستنده‌های خاص (مانند بانک‌ها یا پیش‌شماره‌های خاص)، دکمه «افزودن قانون» را لمس کنید.",
                                fontSize = 12.sp,
                                color = Slate600,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(filteredRules, key = { it.id }) { rule ->
                    RuleItemCard(
                        rule = rule,
                        onToggle = { onToggleRule(rule, it) },
                        onEdit = { editingRule = rule },
                        onDelete = { onDeleteRule(rule) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    // Add Rule Dialog
    if (showAddDialog) {
        RuleEditorDialog(
            initialRule = null,
            onSave = { pattern, matchType, label, keyword ->
                onAddRule(pattern, matchType, label, keyword)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // Edit Rule Dialog
    editingRule?.let { rule ->
        RuleEditorDialog(
            initialRule = rule,
            onSave = { pattern, matchType, label, keyword ->
                onUpdateRule(
                    rule.copy(
                        senderPattern = pattern,
                        matchType = matchType,
                        label = label,
                        keywordFilter = keyword
                    )
                )
                editingRule = null
            },
            onDismiss = { editingRule = null }
        )
    }
}

@Composable
private fun RuleItemCard(
    rule: FilterRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (rule.isEnabled) Color(0x3338BDF8) else Slate800,
                    RoundedCornerShape(16.dp)
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (rule.isEnabled) Color(0x2238BDF8) else Slate800),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = null,
                            tint = if (rule.isEnabled) Sky400 else Slate400,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = rule.label.ifBlank { "Sender Pattern" },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = rule.senderPattern,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Cyan400
                        )
                    }
                }

                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Slate950,
                        checkedTrackColor = Sky400,
                        uncheckedThumbColor = Slate400,
                        uncheckedTrackColor = Slate800
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Badges row: Match type + keyword filter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val matchTypePersian = when (rule.matchType) {
                    MatchType.EXACT -> "تطابق کامل شماره"
                    MatchType.PREFIX -> "پیش‌شماره"
                    MatchType.CONTAINS -> "شامل متن"
                    MatchType.REGEX -> "عبارت باقاعده"
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x226366F1))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "نوع تطابق: $matchTypePersian",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Indigo400
                    )
                }

                if (rule.keywordFilter.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x2210B981))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "کلمات کلیدی: ${rule.keywordFilter}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Emerald400
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "ویرایش قانون",
                        tint = Slate400,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "حذف قانون",
                        tint = Rose400,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorDialog(
    initialRule: FilterRule?,
    onSave: (pattern: String, matchType: MatchType, label: String, keyword: String) -> Unit,
    onDismiss: () -> Unit
) {
    var pattern by remember { mutableStateOf(initialRule?.senderPattern ?: "") }
    var matchType by remember { mutableStateOf(initialRule?.matchType ?: MatchType.EXACT) }
    var label by remember { mutableStateOf(initialRule?.label ?: "") }
    var keyword by remember { mutableStateOf(initialRule?.keywordFilter ?: "") }

    var testSenderInput by remember { mutableStateOf("") }
    var testMessageInput by remember { mutableStateOf("") }

    var dropdownExpanded by remember { mutableStateOf(false) }

    // Interactive matcher preview
    val tempRule = remember(pattern, matchType, keyword) {
        FilterRule(
            senderPattern = pattern,
            matchType = matchType,
            label = label,
            keywordFilter = keyword,
            isEnabled = true
        )
    }

    val doesTestMatch = remember(tempRule, testSenderInput, testMessageInput) {
        if (testSenderInput.isBlank()) null
        else tempRule.matches(testSenderInput, testMessageInput)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (initialRule == null) "افزودن قانون شماره جدید" else "ویرایش قانون شماره",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "بستن", tint = Slate400)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("شماره فرستنده یا الگوی شماره") },
                    placeholder = { Text("مثال: +989123456789 یا BANK_MELLI") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Sky400,
                        unfocusedBorderColor = Slate800,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Slate950,
                        unfocusedContainerColor = Slate950
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Match Type Dropdown
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { dropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = when (matchType) {
                            MatchType.EXACT -> "تطابق دقیق شماره (Exact)"
                            MatchType.PREFIX -> "شروع با پیش‌شماره (Prefix)"
                            MatchType.CONTAINS -> "شامل نام یا عبارت (Contains)"
                            MatchType.REGEX -> "عبارت باقاعده (Regex)"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("نوع بررسی شماره") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Sky400,
                            unfocusedBorderColor = Slate800,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Slate950,
                            unfocusedContainerColor = Slate950
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        modifier = Modifier.background(Slate900)
                    ) {
                        DropdownMenuItem(
                            text = { Text("تطابق دقیق شماره (مثال: +989120000000)", color = Color.White) },
                            onClick = {
                                matchType = MatchType.EXACT
                                dropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("شروع با پیش‌شماره (مثال: +98، 0912، 2000)", color = Color.White) },
                            onClick = {
                                matchType = MatchType.PREFIX
                                dropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("شامل نام یا عبارت (مثال: BANK، MELLAT، OTP)", color = Color.White) },
                            onClick = {
                                matchType = MatchType.CONTAINS
                                dropdownExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("عبارت باقاعده رگکس (مثال: ^\\+98[0-9]{10}$)", color = Color.White) },
                            onClick = {
                                matchType = MatchType.REGEX
                                dropdownExpanded = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("نام / برچسب قانون (اختیاری)") },
                    placeholder = { Text("مثال: پیامک‌های رمز یکبار مصرف بانک") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Sky400,
                        unfocusedBorderColor = Slate800,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Slate950,
                        unfocusedContainerColor = Slate950
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("فیلتر کلمات کلیدی در متن پیامک (اختیاری)") },
                    placeholder = { Text("مثال: رمز, کد تایید, واریز (با کاما جدا کنید)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Sky400,
                        unfocusedBorderColor = Slate800,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Slate950,
                        unfocusedContainerColor = Slate950
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Interactive Tester Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Slate950)
                        .border(1.dp, Slate800, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "آزمایش زنده تطابق قانون",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Cyan400
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = testSenderInput,
                            onValueChange = { testSenderInput = it },
                            placeholder = { Text("یک شماره تستی وارد کنید...", fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Sky400,
                                unfocusedBorderColor = Slate800,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (doesTestMatch != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (doesTestMatch) Emerald400 else Rose400)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (doesTestMatch) "قانون با این شماره مطابقت دارد و ارسال می‌شود!" else "شماره با این قانون مطابقت ندارد",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (doesTestMatch) Emerald400 else Rose400
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        if (pattern.isNotBlank()) {
                            onSave(pattern.trim(), matchType, label.trim(), keyword.trim())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Sky400, contentColor = Slate950),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (initialRule == null) "ایجاد قانون" else "ذخیره تغییرات",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
