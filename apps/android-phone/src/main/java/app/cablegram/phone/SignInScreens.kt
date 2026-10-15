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
    val signIn = viewModel.signIn
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    val canSend = email.trim().contains("@") && !signIn.busy
    SignInPage {
        BrandMark()
        Spacer(Modifier.height(12.dp))
        ScreenHeading("Your library.\nYour big screen.", "Sign in or create an account to bring your own videos together, ready for your TV.")
        if (signIn.methods?.google != null) {
            OutlinedButton(
                onClick = { viewModel.signInWithGoogle(context) },
                enabled = !signIn.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro("sign_in_google"),
            ) {
                Image(painterResource(R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Continue with Google")
            }
            Text("or", color = VlcMuted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth().maestro("sign_in_email"),
            singleLine = true,
            label = { Text("Email address") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) viewModel.requestSignInCode(email) }),
        )
        Button(
            onClick = { viewModel.requestSignInCode(email) },
            enabled = canSend,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro("sign_in_send_code"),
        ) { BusyLabel(signIn.busy, "Email me a code") }
        (signIn.message ?: viewModel.status)?.let { StatusNote(it) }
        PrivacyLine(signIn.documents)
    }
}

@Composable
private fun CodeForm(viewModel: PhoneViewModel, step: SignInStep.Code) {
    val signIn = viewModel.signIn
    var code by rememberSaveable(step.email) { mutableStateOf("") }
    BackHandler { signIn.back() }
    SignInPage {
        BrandMark()
        ScreenHeading("Check your email", "Enter the 6-digit code we sent to ${step.email}. It expires in 15 minutes.")
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            modifier = Modifier.fillMaxWidth().maestro("sign_in_code"),
            singleLine = true,
            label = { Text("Code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (code.length == 6 && !signIn.busy) viewModel.verifySignInCode(code) }),
        )
        Button(
            onClick = { viewModel.verifySignInCode(code) },
            enabled = code.length == 6 && !signIn.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro("sign_in_code_submit"),
        ) { BusyLabel(signIn.busy, "Continue") }
        signIn.message?.let { StatusNote(it) }
        TextButton(onClick = viewModel::resendSignInCode, enabled = !signIn.busy, modifier = Modifier.fillMaxWidth()) { Text("Send a new code") }
        TextButton(onClick = signIn::back, enabled = !signIn.busy, modifier = Modifier.fillMaxWidth().maestro("sign_in_other_email")) {
            Text("Use a different email")
        }
    }
}

/** One unticked box for 18+ and the Terms; nothing is stored on the server until it is ticked and sent. */
@Composable
private fun CreateAccountForm(viewModel: PhoneViewModel, step: SignInStep.CreateAccount) {
    val signIn = viewModel.signIn
    var name by rememberSaveable(step.ticket) { mutableStateOf(step.name) }
    var agreed by rememberSaveable(step.ticket) { mutableStateOf(false) }
    val canCreate = agreed && name.trim().isNotEmpty() && !signIn.busy
    BackHandler { signIn.back() }
    SignInPage {
        BrandMark()
        ScreenHeading("Create your account", "For ${step.email}.")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(80) },
            modifier = Modifier.fillMaxWidth().maestro("sign_up_name"),
            singleLine = true,
            label = { Text("Your name") },
            supportingText = { Text("Shown on your phones and TVs") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        AgreeRow(agreed, { agreed = it }, "I'm 18 or older and I accept the ", signIn.documents.terms.url, "sign_up_agree")
        Button(
            onClick = { viewModel.createAccount(name) },
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro("sign_up_create"),
        ) { BusyLabel(signIn.busy, "Create account") }
        signIn.message?.let { StatusNote(it) }
        PrivacyLine(signIn.documents)
        TextButton(onClick = signIn::back, enabled = !signIn.busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

/** New Terms: nothing else until they are accepted, except deleting the account or signing out (spec 007 US4). */
@Composable
internal fun TermsScreen(viewModel: PhoneViewModel) {
    val uri = LocalUriHandler.current
    var agreed by rememberSaveable { mutableStateOf(false) }
    SignInPage {
        BrandMark()
        ScreenHeading("Our Terms changed", "Please read the new Terms of Service. You can keep using Cablegram once you accept them.")
        AgreeRow(agreed, { agreed = it }, "I accept the ", viewModel.signIn.documents.terms.url, "terms_agree")
        Button(
            onClick = viewModel::acceptTerms,
            enabled = agreed && !viewModel.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro("terms_accept"),
        ) { BusyLabel(viewModel.busy, "Accept") }
        viewModel.status?.let { StatusNote(it) }
        PrivacyLine(viewModel.signIn.documents)
        TextButton(onClick = { uri.openUri(DELETE_ACCOUNT_WEB_URL) }, modifier = Modifier.fillMaxWidth()) { Text("Delete my account") }
        TextButton(onClick = viewModel::signOut, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}

/** A checkbox whose sentence ends with a link to the Terms; tapping the sentence ticks the box, the link opens them. */
@Composable
private fun AgreeRow(checked: Boolean, onChange: (Boolean) -> Unit, lead: String, termsUrl: String, tag: String) {
    val uri = LocalUriHandler.current
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) }.maestro(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onChange)
            Text(lead + "Terms of Service.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { uri.openUri(termsUrl) }, modifier = Modifier.padding(start = 36.dp)) { Text("Read the Terms of Service") }
    }
}

/** The privacy notice, shown before any personal data is sent (spec 007 US3). */
@Composable
private fun PrivacyLine(documents: LegalDocuments) {
    val uri = LocalUriHandler.current
    val text = buildAnnotatedString {
        append("How Cablegram uses your data: ")
        withStyle(SpanStyle(color = VlcOrange, textDecoration = TextDecoration.Underline)) { append("Privacy Policy") }
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
    if (busy) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("Please wait…")
    } else {
        Text(label)
    }
}
