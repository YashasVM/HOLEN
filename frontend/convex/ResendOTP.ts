import { Email } from "@convex-dev/auth/providers/Email";
import { Resend as ResendAPI } from "resend";

declare const process: { env: Record<string, string | undefined> };

export const ResendOTP = Email({
  id: "resend-otp",
  apiKey: process.env.AUTH_RESEND_KEY,
  async sendVerificationRequest({ identifier: email, url, token }) {
    const resend = new ResendAPI(process.env.AUTH_RESEND_KEY);
    await resend.emails.send({
      from: "HOLEN <onboarding@resend.dev>",
      to: [email],
      subject: "Your HOLEN sign-in code",
      html: otpEmail(token),
    });
  },
});

function otpEmail(code: string): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>Sign-in Code</title>
</head>
<body style="margin:0;padding:0;background:#0a0a0a;font-family:'DM Sans',Arial,sans-serif;">
  <table width="100%" cellpadding="0" cellspacing="0" style="background:#0a0a0a;padding:40px 0;">
    <tr><td align="center">
      <table width="480" cellpadding="0" cellspacing="0" style="background:#111;border:2px solid #222;max-width:480px;width:100%;">

        <!-- Bauhaus header bar -->
        <tr>
          <td style="padding:0;">
            <table width="100%" cellpadding="0" cellspacing="0">
              <tr>
                <td style="background:#e63329;width:33.33%;height:6px;"></td>
                <td style="background:#1d7ce0;width:33.33%;height:6px;"></td>
                <td style="background:#f0c419;width:33.33%;height:6px;"></td>
              </tr>
            </table>
          </td>
        </tr>

        <!-- Logo row -->
        <tr>
          <td style="padding:32px 40px 0;">
            <table cellpadding="0" cellspacing="0">
              <tr>
                <td style="background:#e63329;width:36px;height:36px;text-align:center;vertical-align:middle;">
                  <span style="color:#fff;font-size:18px;font-weight:900;line-height:36px;">▼</span>
                </td>
                <td style="padding-left:12px;">
                  <div style="color:#fff;font-size:18px;font-weight:900;letter-spacing:0.08em;text-transform:uppercase;">HOLEN</div>
                  <div style="color:#666;font-size:11px;letter-spacing:0.12em;text-transform:uppercase;margin-top:1px;">Private Downloader</div>
                </td>
              </tr>
            </table>
          </td>
        </tr>

        <!-- Title -->
        <tr>
          <td style="padding:28px 40px 0;">
            <div style="color:#999;font-size:11px;letter-spacing:0.14em;text-transform:uppercase;margin-bottom:8px;">Sign-in code</div>
            <div style="color:#fff;font-size:22px;font-weight:700;line-height:1.2;">Your one-time<br/>access code</div>
          </td>
        </tr>

        <!-- OTP code block -->
        <tr>
          <td style="padding:24px 40px;">
            <table cellpadding="0" cellspacing="0" style="border:2px solid #e63329;">
              <tr>
                <td style="padding:20px 32px;">
                  <div style="color:#999;font-size:10px;letter-spacing:0.16em;text-transform:uppercase;margin-bottom:10px;">Enter this code</div>
                  <div style="color:#fff;font-size:42px;font-weight:900;letter-spacing:0.18em;font-variant-numeric:tabular-nums;">${code}</div>
                </td>
                <!-- Bauhaus accent square -->
                <td style="background:#e63329;width:8px;"></td>
              </tr>
            </table>
          </td>
        </tr>

        <!-- Info -->
        <tr>
          <td style="padding:0 40px 32px;">
            <div style="color:#555;font-size:13px;line-height:1.6;">
              This code expires in <strong style="color:#888;">10 minutes</strong>.<br/>
              If you didn't request this, ignore this email.
            </div>
          </td>
        </tr>

        <!-- Bottom bar -->
        <tr>
          <td style="padding:0;">
            <table width="100%" cellpadding="0" cellspacing="0">
              <tr>
                <td style="background:#f0c419;height:3px;"></td>
              </tr>
            </table>
          </td>
        </tr>

      </table>
    </td></tr>
  </table>
</body>
</html>`;
}
