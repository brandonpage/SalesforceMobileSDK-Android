package com.salesforce.androidsdk.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.salesforce.androidsdk.ui.theme.WebviewTheme

class JetpackLoginActivity : ComponentActivity() {
    private val viewModel = LoginViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.loading.value = true

        setContentView(
            ComposeView(this).apply {
                setContent {
                    WebviewTheme {
                        LoginView(viewModel)
                    }
                }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Preview
    @Composable
    fun LoginView(viewModel: LoginViewModel = LoginViewModel()) {
        var showMenu by remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState()
        val showTopAppBar = true
        val showBottomAppBar = false

        // Temp data
        val servers = listOf(
            Pair("login.salesforce.com", "Production"),
            Pair("test.salesforce.com", "Sandbox"),
            Pair("msdk-enhanced-dev-ed.my.salesforce.com", "Site"),
            Pair("https://msdk-enhanced-dev-ed.my.site.com/headless/login", "Community"),
        )

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    expandedHeight = if (showTopAppBar) TopAppBarDefaults.TopAppBarExpandedHeight else 0.dp,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = viewModel.backgroundColor.value),
                    title = {
                        Text(
                            viewModel.selectedSever.value,
                            color = viewModel.headerTextColor.value,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    actions = @Composable {
                        IconButton(
                            onClick = { showMenu = !showMenu },
                            colors = IconButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = viewModel.headerTextColor.value,
                                disabledContainerColor = Color.Transparent,
                                disabledContentColor = Color.Transparent,
                            ),
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More Options")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = Color.White,
                        ) {
                            DropdownMenuItem(
                                text = { Text("Change Server", color = Color.Gray) },
                                onClick = {
                                    viewModel.showBottomSheet.value = true
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                onClick = { /* */ },
                                text = { Text("Clear Cookies", color = Color.Gray) },
                            )
                            DropdownMenuItem(
                                    onClick = {
                                        this@JetpackLoginActivity.startActivity(
                                            Intent(baseContext, JetpackUserSwitcherActivity::class.java)
                                        )
                                    },
                            text = { Text("Reload", color = Color.Gray) },
                            )
                        }
                    },
                    // conditionally show this when we need a back button
                    navigationIcon = {
                        IconButton(
                            onClick = { /* Open Server Picker */ },
                            colors = IconButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = viewModel.headerTextColor.value,
                                disabledContainerColor = Color.Transparent,
                                disabledContentColor = Color.Transparent,
                            ),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                )
            },
            bottomBar = {
                BottomAppBar(containerColor = viewModel.backgroundColor.value) {
                    // IDP and Bio Auth buttons here
                    if (showBottomAppBar) {
                        Button(
                            onClick = { /* Save new server */ },
                            modifier = Modifier.padding(20.dp).fillMaxWidth(),
                            colors = ButtonColors(
                                containerColor = Color.Black,
                                contentColor = Color.Black,
                                disabledContainerColor = Color.Black,
                                disabledContentColor = Color.Black
                            )
                        ) {
                            Text(text = "Save", color = Color.White)
                        }
                    }
                }
            },
        ) { innerPadding ->
            Webview(innerPadding)

            if (viewModel.showBottomSheet.value) {
                ModalBottomSheet(
                    onDismissRequest = {
                        viewModel.showBottomSheet.value = false
                    },
                    sheetState = sheetState,
                    containerColor = Color.White,
                ) {
                    var editing by remember { mutableStateOf(false) }

                    // Sheet content
                    if (editing) {
                        var name by remember { mutableStateOf("") }
                        var url by remember { mutableStateOf("") }

                        IconButton(onClick = { editing = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.Black)
                        }

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name") },
                            modifier =  Modifier.padding(start = 15.dp, end = 15.dp, top = 15.dp).fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("Url") },
                            modifier =  Modifier.padding(start = 15.dp, end = 15.dp, bottom = 15.dp).fillMaxWidth(),
                        )
                        Button(
                            onClick = { /* Save new server */ },
                            modifier = Modifier.padding(20.dp).fillMaxWidth(),
                            colors = ButtonColors(
                                containerColor = Color.Black,
                                contentColor = Color.Black,
                                disabledContainerColor = Color.Black,
                                disabledContentColor = Color.Black
                            )
                        ) {
                            Text(text = "Save", color = Color.White)
                        }
                    } else {
                        Column {
                            Text(
                                text = "Change Server",
                                color = Color.Black,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.padding(10.dp))
                            servers.forEach {
                                HorizontalDivider(thickness = 1.dp)
                                LoginServer(it.first, it.second)
                            }

                            TextButton(
                                modifier = Modifier.padding(bottom = 20.dp),
                                onClick = {
                                    editing = true
                                }
                            ) {
                                Text(
                                    text = "Add New Connection",
                                    color = Color.Black,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Preview
    @Composable
    fun LoginServer(url: String = "login.salesforce.com", name: String = "Production") {
        Card(
            modifier = Modifier.padding(top = 10.dp, bottom = 10.dp).fillMaxWidth().clickable {
                viewModel.selectedSever.value = url
                viewModel.showBottomSheet.value = false
                viewModel.loading.value = true
                viewModel.backgroundColor.value = Color.Black
            },
            colors = CardColors(
                containerColor = Color.White,
                contentColor = Color.Black,
                disabledContentColor = Color.Gray,
                disabledContainerColor = Color.Black,
            ),
            shape = RectangleShape,
        ) {
            Text(name, modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 15.dp), fontSize = 22.sp)
            Text(url, modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 15.dp), fontStyle = FontStyle.Italic)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun Webview(paddingValues: PaddingValues) {
        AndroidView(
            modifier = Modifier.padding(paddingValues).alpha(
                if (viewModel.loading.value) 0.0f else 100.0f
            ),
            factory = {
                val webView = WebView(it).apply {
                    this.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    this.webViewClient = CustomWebViewClient(viewModel)
                }
                webView.setBackgroundColor(Color.Transparent.toArgb())
                webView.settings.javaScriptEnabled = true
                webView
            }, update = {
                it.loadUrl(viewModel.selectedSever.value)
            })
    }

    class CustomWebViewClient(private var viewModel: LoginViewModel): WebViewClient(){
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            return url != null && url.startsWith("https://google.com")
        }


        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.evaluateJavascript(
                "(function() { return window.getComputedStyle( document.body ,null).getPropertyValue('background-color'); })();"
            ) { result ->
                viewModel.backgroundColor.value = validateAndExtractBackgroundColor(result) ?: return@evaluateJavascript
            }

            viewModel.loading.value = false
        }

        private fun validateAndExtractBackgroundColor(javaScriptResult: String): Color? {
            // This parses the expected "rgb(x, x, x)" string.
            val rgbTextPattern = "rgb\\((\\d{1,3}), (\\d{1,3}), (\\d{1,3})\\)".toRegex()
            val rgbMatch = rgbTextPattern.find(javaScriptResult)

            // groupValues[0] is the entire match.  [1] is red, [2] is green, [3] is green.
            rgbMatch?.groupValues?.get(3) ?: return null
            val red = rgbMatch.groupValues[1].toIntOrNull() ?: return null
            val green = rgbMatch.groupValues[2].toIntOrNull() ?: return null
            val blue = rgbMatch.groupValues[3].toIntOrNull() ?: return null

            return Color(red, green, blue)
        }
    }
}