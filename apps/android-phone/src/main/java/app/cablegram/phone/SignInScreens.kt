package app.cablegram.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Spec 007: Continue with Google, or an email code; then Create your account for a new person. */
@Composable
internal fun SignInScreen(viewModel: PhoneViewModel) {
    when (val step = viewModel.signIn.step) {
        SignInStep.Start -> StartForm(viewModel)
        is SignInStep.Code -> CodeForm(viewModel, step)
        is SignInStep.CreateAccount -> CreateAccountForm(viewModel, step)
    }
}

@Composable
private fun SignInPage(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(VlcBlack).safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

@Composable
private fun StartForm(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val signIn = viewModel.signIn
    val context = androidx.activity.compose.LocalActivity.current ?: LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    val canSend = email.trim().contains("@") && !signIn.busy
    SignInPage {
        BrandMark()
        Spacer(Modifier.height(12.dp))
        ScreenHeading(ui.getString(R.string.ui_your_library_your_big_screen_1ff86b), ui.getString(R.string.ui_sign_in_or_create_an_account_to_bring_your_own_videos__e9e560))
        if (signIn.methods?.google != null) {
            OutlinedButton(
                onClick = { viewModel.signInWithGoogle(context) },
                enabled = !signIn.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro("sign_in_google"),
            ) {
                Image(painterResource(R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(ui.getString(R.string.ui_continue_with_google_cce937))
            }
            Text(ui.getString(R.string.ui_or_717551), color = VlcMuted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth().maestro("sign_in_email"),
            singleLine = true,
            label = { Text(ui.getString(R.string.ui_email_address_f2488f)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) viewModel.requestSignInCode(email) }),
        )
        Button(
            onClick = { viewModel.requestSignInCode(email) },
            enabled = canSend,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro("sign_in_send_code"),
        ) { BusyLabel(signIn.busy, ui.getString(R.string.ui_email_me_a_code_88d420)) }
        (signIn.message ?: viewModel.status)?.let { StatusNote(it) }
        PrivacyLine(signIn.documents)
    }
}

@Composable
private fun CodeForm(viewModel: PhoneViewModel, step: SignInStep.Code) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val signIn = viewModel.signIn
    var code by rememberSaveable(step.email) { mutableStateOf("") }
    BackHandler { signIn.back() }
    SignInPage {
        BrandMark()
        ScreenHeading(ui.getString(R.string.ui_check_your_email_773228), ui.getString(R.string.ui_enter_the_6_digit_code_we_sent_to_it_expires_in_15_min_89e634, step.email))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            modifier = Modifier.fillMaxWidth().maestro("sign_in_code"),
            singleLine = true,
            label = { Text(ui.getString(R.string.ui_code_340f46)) },
            textStyle = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (code.length == 6 && !signIn.busy) viewModel.verifySignInCode(code) }),
        )
        Button(
            onClick = { viewModel.verifySignInCode(code) },
            enabled = code.length == 6 && !signIn.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro("sign_in_code_submit"),
        ) { BusyLabel(signIn.busy, ui.getString(R.string.ui_continue_31fbef)) }
        signIn.message?.let { StatusNote(it) }
        TextButton(onClick = viewModel::resendSignInCode, enabled = !signIn.busy, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_send_a_new_code_9200fc)) }
        TextButton(onClick = signIn::back, enabled = !signIn.busy, modifier = Modifier.fillMaxWidth().maestro("sign_in_other_email")) {
            Text(ui.getString(R.string.ui_use_a_different_email_b73370))
        }
    }
}

/** One unticked box for 18+ and the Terms; nothing is stored on the server until it is ticked and sent. */
@Composable
private fun CreateAccountForm(viewModel: PhoneViewModel, step: SignInStep.CreateAccount) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val signIn = viewModel.signIn
    var name by rememberSaveable(step.ticket) { mutableStateOf(step.name) }
    var agreed by rememberSaveable(step.ticket) { mutableStateOf(false) }
    val canCreate = agreed && name.trim().isNotEmpty() && !signIn.busy
    BackHandler { signIn.back() }
    SignInPage {
        BrandMark()
        ScreenHeading(ui.getString(R.string.ui_create_your_account_9e7093), ui.getString(R.string.ui_for_f4a63c, step.email))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(80) },
            modifier = Modifier.fillMaxWidth().maestro("sign_up_name"),
            singleLine = true,
            label = { Text(ui.getString(R.string.ui_your_name_2c6b2e)) },
            supportingText = { Text(ui.getString(R.string.ui_shown_on_your_phones_and_tvs_b7ea60)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        AgreeRow(agreed, { agreed = it }, ui.getString(R.string.ui_i_m_18_or_older_and_i_accept_the_9b6ca5), signIn.documents.terms.url, "sign_up_agree")
        Button(
            onClick = { viewModel.createAccount(name) },
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro("sign_up_create"),
        ) { BusyLabel(signIn.busy, ui.getString(R.string.ui_create_account_798ca2)) }
        signIn.message?.let { StatusNote(it) }
        PrivacyLine(signIn.documents)
        TextButton(onClick = signIn::back, enabled = !signIn.busy, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_cancel_19766e)) }
    }
}

/** New Terms: nothing else until they are accepted, except deleting the account or signing out (spec 007 US4). */
@Composable
internal fun TermsScreen(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val uri = LocalUriHandler.current
    var agreed by rememberSaveable { mutableStateOf(false) }
    SignInPage {
        BrandMark()
        ScreenHeading(ui.getString(R.string.ui_our_terms_changed_edc5de), ui.getString(R.string.ui_please_read_the_new_terms_of_service_you_can_keep_usin_5c12c3))
        AgreeRow(agreed, { agreed = it }, ui.getString(R.string.ui_i_accept_the_5ce6ca), viewModel.signIn.documents.terms.url, "terms_agree")
        Button(
            onClick = viewModel::acceptTerms,
            enabled = agreed && !viewModel.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro("terms_accept"),
        ) { BusyLabel(viewModel.busy, "Accept") }
        viewModel.status?.let { StatusNote(it) }
        PrivacyLine(viewModel.signIn.documents)
        TextButton(onClick = { uri.openUri(DELETE_ACCOUNT_WEB_URL) }, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_delete_my_account_21d529)) }
        TextButton(onClick = viewModel::signOut, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_sign_out_48f0d3)) }
    }
}

/** A checkbox whose sentence ends with a link to the Terms; tapping the sentence ticks the box, the link opens them. */
@Composable
private fun AgreeRow(checked: Boolean, onChange: (Boolean) -> Unit, lead: String, termsUrl: String, tag: String) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val uri = LocalUriHandler.current
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) }.maestro(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onChange)
            Text(lead + ui.getString(R.string.ui_terms_of_service_d9f3cc), color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { uri.openUri(termsUrl) }, modifier = Modifier.padding(start = 36.dp)) { Text(ui.getString(R.string.ui_read_the_terms_of_service_4e316c)) }
    }
}

/** The privacy notice, shown before any personal data is sent (spec 007 US3). */
@Composable
private fun PrivacyLine(documents: LegalDocuments) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val uri = LocalUriHandler.current
    val text = buildAnnotatedString {
        append(ui.getString(R.string.ui_how_cablegram_uses_your_data_89560e))
        withStyle(SpanStyle(color = VlcOrange, textDecoration = TextDecoration.Underline)) { append(ui.getString(R.string.ui_privacy_policy_506ff3)) }
        append(".")
    }
    Text(
        text,
        color = VlcMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.clickable { uri.openUri(documents.privacy.url) }.maestro("privacy_policy"),
    )
}

@Composable
private fun BusyLabel(busy: Boolean, label: String) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    if (busy) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(ui.getString(R.string.ui_please_wait_4660a9))
    } else {
        Text(label)
    }
}
