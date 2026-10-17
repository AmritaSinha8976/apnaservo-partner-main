package com.apnaservo.partner;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Cashfree hosted checkout for the NEW partner-subscription payment flow.
 *
 * This is intentionally separate from the existing Razorpay checkout screen,
 * which stays untouched. It uses the official Cashfree v3 JS SDK loaded from
 * Cashfree's CDN inside a system WebView — no new Gradle dependency is added.
 *
 * The caller passes a payment_session_id and the environment; the WebView runs
 * cashfree.checkout(...) and reports the outcome back through a result code.
 * The backend remains the source of truth: the caller must still verify the
 * order server-side via /partners/billing/cashfree/verify after this returns.
 */
public class CashfreeCheckoutActivity extends Activity {

    public static final String EXTRA_PAYMENT_SESSION_ID = "payment_session_id";
    public static final String EXTRA_ENV = "env"; // "SANDBOX" | "PRODUCTION"
    public static final String EXTRA_ORDER_ID = "order_id";
    public static final String RESULT_ORDER_ID = "result_order_id";

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String sessionId = getIntent().getStringExtra(EXTRA_PAYMENT_SESSION_ID);
        String env = getIntent().getStringExtra(EXTRA_ENV);
        String orderId = getIntent().getStringExtra(EXTRA_ORDER_ID);

        if (TextUtils.isEmpty(sessionId)) {
            finishWith(RESULT_CANCELED, orderId);
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.WHITE);

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.addJavascriptInterface(new CheckoutBridge(orderId), "AndroidCheckout");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Keep navigation inside the WebView so UPI/bank return URLs are
                // handled by the Cashfree SDK rather than leaving the app.
                return false;
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        webView.loadDataWithBaseURL(
                "https://sdk.cashfree.com",
                buildCheckoutHtml(sessionId, env),
                "text/html",
                "utf-8",
                null);
    }

    private String buildCheckoutHtml(String sessionId, String env) {
        String mode = "PRODUCTION".equalsIgnoreCase(env) ? "production" : "sandbox";
        String safeSession = sessionId.replace("\"", "\\\"");
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<script src=\"https://sdk.cashfree.com/js/v3/cashfree.js\"></script>"
                + "</head><body style=\"margin:0;background:#ffffff\">"
                + "<script>"
                + "function done(status){ try{ AndroidCheckout.onResult(status); }catch(e){} }"
                + "try{"
                + "  var cashfree = Cashfree({ mode: \"" + mode + "\" });"
                + "  cashfree.checkout({ paymentSessionId: \"" + safeSession + "\", redirectTarget: \"_self\" })"
                + "    .then(function(result){"
                + "       if(result && result.error){ done(\"failed\"); }"
                + "       else { done(\"success\"); }"
                + "    })"
                + "    .catch(function(){ done(\"failed\"); });"
                + "}catch(e){ done(\"failed\"); }"
                + "</script></body></html>";
    }

    private void finishWith(int resultCode, String orderId) {
        android.content.Intent data = new android.content.Intent();
        data.putExtra(RESULT_ORDER_ID, orderId == null ? "" : orderId);
        setResult(resultCode, data);
        finish();
    }

    @Override
    public void onBackPressed() {
        finishWith(RESULT_CANCELED, getIntent().getStringExtra(EXTRA_ORDER_ID));
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private class CheckoutBridge {
        private final String orderId;

        CheckoutBridge(String orderId) {
            this.orderId = orderId;
        }

        @JavascriptInterface
        public void onResult(String status) {
            // Regardless of the SDK-reported status we always return to the
            // caller, which re-verifies the order with the backend (the real
            // source of truth). "success" maps to RESULT_OK so the caller
            // verifies immediately; anything else is treated as cancelled.
            runOnUiThread(() -> finishWith("success".equals(status) ? RESULT_OK : RESULT_CANCELED, orderId));
        }
    }
}
