package com.example.appmind

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleClient: GoogleSignInClient
    private lateinit var fbCallbackManager: CallbackManager

    private val googleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken != null) {
                signInFirebaseWithGoogle(idToken)
            } else {
                toast("No se recibió el ID Token de Google")
            }
        } catch (e: ApiException) {
            Log.e("GoogleSignIn", "Fallo: ${e.statusCode}", e)
            toast("Google cancelado o fallido")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Ajuste de barras del sistema (tu layout ya usa @id/main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        // Firebase
        auth = FirebaseAuth.getInstance()

        // ---- Google Sign-In (para Firebase) ----
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)) // viene del google-services.json
            .requestEmail()
            .build()
        googleClient = GoogleSignIn.getClient(this, gso)

        // ---- Facebook Login ----
        fbCallbackManager = CallbackManager.Factory.create()

        // Clicks de los botones
        findViewById<ImageButton>(R.id.btnGoogle).setOnClickListener {
            val intent = googleClient.signInIntent
            googleLauncher.launch(intent)
        }

        findViewById<ImageButton>(R.id.btnFacebook).setOnClickListener {
            // permisos mínimos recomendados
            LoginManager.getInstance()
                .logInWithReadPermissions(this, listOf("email", "public_profile"))

            // registra el callback para este flujo
            LoginManager.getInstance().registerCallback(fbCallbackManager,
                object : FacebookCallback<LoginResult> {
                    override fun onSuccess(result: LoginResult) {
                        val token = result.accessToken
                        val credential = FacebookAuthProvider.getCredential(token.token)
                        auth.signInWithCredential(credential)
                            .addOnCompleteListener(this@MainActivity) { task ->
                                if (task.isSuccessful) {
                                    toast("Bienvenido, ${auth.currentUser?.displayName ?: "Usuario"}")
                                    goToHome()
                                } else {
                                    Log.e("FBAuth", "Firebase con Facebook falló", task.exception)
                                    toast("No se pudo iniciar con Facebook")
                                }
                            }
                    }

                    override fun onCancel() {
                        toast("Facebook cancelado")
                    }

                    override fun onError(error: FacebookException) {
                        Log.e("FacebookLogin", "Error", error)
                        toast("Error de Facebook: ${error.localizedMessage}")
                    }
                })
        }

        // Si ya hay sesión, puedes saltar directo
        auth.currentUser?.let {
            // goToHome() // descomenta si quieres saltar la pantalla al tener sesión
        }
    }

    private fun signInFirebaseWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    toast("Bienvenido, ${auth.currentUser?.displayName ?: "Usuario"}")
                    goToHome()
                } else {
                    Log.e("GAuth", "Firebase con Google falló", task.exception)
                    toast("No se pudo iniciar con Google")
                }
            }
    }

    private fun goToHome() {
        // TODO: navega a tu actividad principal
        // startActivity(Intent(this, HomeActivity::class.java))
        // finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // Necesario para que Facebook reciba el resultado del Activity
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        fbCallbackManager.onActivityResult(requestCode, resultCode, data)
    }
}
