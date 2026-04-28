"""Generate the AURIGA project briefs as PDFs."""
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import mm
from reportlab.lib.colors import HexColor, black, white
from reportlab.lib.enums import TA_LEFT, TA_CENTER
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
    PageBreak, KeepTogether, HRFlowable
)

INK = HexColor("#0a0f1c")
ACCENT = HexColor("#06b6d4")
MUTED = HexColor("#64748b")
RULE = HexColor("#cbd5e1")
PANEL = HexColor("#f1f5f9")


def build_styles():
    base = getSampleStyleSheet()
    styles = {}
    styles["title"] = ParagraphStyle(
        "title", parent=base["Title"],
        fontName="Helvetica-Bold", fontSize=22, leading=26,
        textColor=INK, alignment=TA_LEFT, spaceAfter=2,
    )
    styles["subtitle"] = ParagraphStyle(
        "subtitle", parent=base["Normal"],
        fontName="Helvetica", fontSize=10.5, leading=14,
        textColor=MUTED, alignment=TA_LEFT, spaceAfter=14,
    )
    styles["h1"] = ParagraphStyle(
        "h1", parent=base["Heading1"],
        fontName="Helvetica-Bold", fontSize=13, leading=17,
        textColor=ACCENT, spaceBefore=14, spaceAfter=6,
    )
    styles["h2"] = ParagraphStyle(
        "h2", parent=base["Heading2"],
        fontName="Helvetica-Bold", fontSize=11, leading=14,
        textColor=INK, spaceBefore=10, spaceAfter=4,
    )
    styles["body"] = ParagraphStyle(
        "body", parent=base["BodyText"],
        fontName="Helvetica", fontSize=10, leading=14.5,
        textColor=INK, spaceAfter=6,
    )
    styles["bullet"] = ParagraphStyle(
        "bullet", parent=base["BodyText"],
        fontName="Helvetica", fontSize=10, leading=14.5,
        textColor=INK, leftIndent=14, bulletIndent=2, spaceAfter=3,
    )
    styles["mono"] = ParagraphStyle(
        "mono", parent=base["Code"],
        fontName="Courier-Bold", fontSize=10.5, leading=15,
        textColor=INK, leftIndent=10, spaceAfter=4,
    )
    styles["footer"] = ParagraphStyle(
        "footer", parent=base["Normal"],
        fontName="Helvetica", fontSize=8, leading=10,
        textColor=MUTED, alignment=TA_CENTER,
    )
    styles["callout"] = ParagraphStyle(
        "callout", parent=base["BodyText"],
        fontName="Helvetica-Oblique", fontSize=10, leading=14,
        textColor=INK, leftIndent=10, rightIndent=10,
        spaceBefore=4, spaceAfter=8,
    )
    return styles


def page_decoration(canvas, doc):
    canvas.saveState()
    w, h = A4
    canvas.setFillColor(INK)
    canvas.rect(0, h - 14 * mm, w, 14 * mm, fill=1, stroke=0)
    canvas.setFillColor(white)
    canvas.setFont("Helvetica-Bold", 10)
    canvas.drawString(18 * mm, h - 9 * mm, "AURIGA  //  DRAKOSANCTIS")
    canvas.setFillColor(ACCENT)
    canvas.setFont("Helvetica", 9)
    canvas.drawRightString(w - 18 * mm, h - 9 * mm, doc.brief_tag)
    canvas.setStrokeColor(RULE)
    canvas.setLineWidth(0.5)
    canvas.line(18 * mm, 16 * mm, w - 18 * mm, 16 * mm)
    canvas.setFillColor(MUTED)
    canvas.setFont("Helvetica", 8)
    canvas.drawString(18 * mm, 11 * mm, doc.brief_footer_left)
    canvas.drawRightString(w - 18 * mm, 11 * mm, f"Page {doc.page}")
    canvas.restoreState()


def panel(text, styles, bg=PANEL):
    p = Paragraph(text, styles["callout"])
    t = Table([[p]], colWidths=[170 * mm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), bg),
        ("BOX", (0, 0), (-1, -1), 0, white),
        ("LEFTPADDING", (0, 0), (-1, -1), 10),
        ("RIGHTPADDING", (0, 0), (-1, -1), 10),
        ("TOPPADDING", (0, 0), (-1, -1), 8),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
    ]))
    return t


def hr():
    return HRFlowable(width="100%", thickness=0.4, color=RULE,
                      spaceBefore=6, spaceAfter=6)


def bullets(items, styles):
    return [Paragraph(f"&bull;&nbsp;&nbsp;{x}", styles["bullet"]) for x in items]


# ---------- Brief 1: Project Brief ----------

def build_project_brief(path):
    styles = build_styles()
    doc = SimpleDocTemplate(
        path, pagesize=A4,
        leftMargin=18 * mm, rightMargin=18 * mm,
        topMargin=22 * mm, bottomMargin=20 * mm,
    )
    doc.brief_tag = "PROJECT BRIEF  v1.0"
    doc.brief_footer_left = "AURIGA Phase 1 // For Trae context"

    s = []
    s.append(Paragraph("AURIGA — Phase 1 Project Brief", styles["title"]))
    s.append(Paragraph(
        "Spatial intelligence for visually impaired users in Nairobi. "
        "Solo build. Offline-first. APK distribution. Pilot trio: Inable, KSB, one local school.",
        styles["subtitle"]))

    s.append(Paragraph("Mission", styles["h1"]))
    s.append(Paragraph(
        "Build the only mobile app a blind user in Nairobi reaches for every day to "
        "<b>read their world</b> and <b>find things in space</b>. Compete on persistent "
        "geometric awareness — bearing, distance, and what's there — not on cloud horsepower.",
        styles["body"]))

    s.append(Paragraph("Target user", styles["h1"]))
    s.extend(bullets([
        "Visually impaired adults in Nairobi using mid-range Android phones (Tecno Spark, Infinix Hot, Samsung A-series, Itel, etc.).",
        "Limited or expensive mobile data — assume offline by default, sync when on Wi-Fi.",
        "Mostly solo navigation use: matatus, markets, sidewalks, indoor spaces.",
        "Sideloads APKs from WhatsApp / NGO / friend. No Play Store assumption.",
    ], styles))

    s.append(Paragraph("The house style (non-negotiable)", styles["h1"]))
    s.append(panel(
        "Every announcement, in every feature, follows the same shape:<br/>"
        "<b>&lt;what&gt; + &lt;distance&gt; + &lt;bearing&gt;</b>.<br/>"
        "If a feature can't say all three, it doesn't ship in that feature.",
        styles))
    s.append(Paragraph("Examples:", styles["h2"]))
    s.append(Paragraph("Object find &nbsp;&rarr;&nbsp; <b>\"Keys, 1.2 meters, 2 o'clock.\"</b>", styles["mono"]))
    s.append(Paragraph("OCR &nbsp;&rarr;&nbsp; <b>\"Sign, 80 centimeters, ahead: 'Tusker 100 bob'.\"</b>", styles["mono"]))
    s.append(Paragraph("Ambient &nbsp;&rarr;&nbsp; <b>\"Chair, half a meter, right.\"</b>", styles["mono"]))
    s.append(Paragraph("SkyShield &nbsp;&rarr;&nbsp; <b>\"Pole, 60 centimeters, ahead, stop.\"</b>", styles["mono"]))

    s.append(Paragraph("Phase 1 — what ships", styles["h1"]))
    data = [
        ["#", "Feature", "What it speaks / does"],
        ["1", "Target-based object finding", "<what> + <distance> + <bearing>, only matched classes"],
        ["2", "On-device OCR + framing guidance", "<text> + <distance> + <bearing>, plus 'move left' / 'hold steady'"],
        ["3", "Audio-first first-run UX", "Speaks on first launch; one-page sighted-helper sheet for the very first install"],
        ["4", "Self-update channel", "Website manifest + in-app check + audio confirm + APK install handoff"],
    ]
    t = Table(data, colWidths=[10 * mm, 55 * mm, 105 * mm])
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), INK),
        ("TEXTCOLOR", (0, 0), (-1, 0), white),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 9.5),
        ("ALIGN", (0, 0), (0, -1), "CENTER"),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [white, PANEL]),
        ("LINEBELOW", (0, 0), (-1, -1), 0.3, RULE),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]))
    s.append(t)

    s.append(Paragraph("Build order (do not parallelize)", styles["h1"]))
    s.extend(bullets([
        "<b>Step 0:</b> Announcement engine — the <i>&lt;what&gt; + &lt;distance&gt; + &lt;bearing&gt;</i> templating layer that all features call. Build this first; both #1 and #2 plug into it.",
        "<b>Step 1:</b> Object finding — exercises the spatial engine, GhostAnchor, phone profiles. Will surface every spatial bug.",
        "<b>Step 2:</b> OCR + framing — reuses camera and audio rails from step 1.",
        "<b>Step 3:</b> First-run UX — only after the above features actually exist to onboard into.",
        "<b>Step 4:</b> Self-update channel — ships last because v1.0 has to exist before there's anything to update from.",
    ], styles))

    s.append(Paragraph("Definition of done", styles["h1"]))
    s.append(panel(
        "<b>I can use AURIGA for a full day in Nairobi, blindfolded, "
        "without uninstalling it.</b><br/>"
        "If it fails the blindfold day, no shipping — no matter how many features are 'finished.'",
        styles))

    s.append(Paragraph("Acceptance criteria (checked weekly)", styles["h2"]))
    s.extend(bullets([
        "<b>Battery & thermals:</b> camera + ML run in bursts; sleep when phone is pocketed (proximity + accelerometer).",
        "<b>Audio fatigue:</b> 'quiet mode' announces only changes, not every frame; user-controllable verbosity.",
        "<b>Recovery:</b> lens covered, sun glare, low light, drop, pocket — app says what's wrong, never goes silent.",
        "<b>Movement:</b> works while walking. Bearing stability matters more than absolute accuracy.",
        "<b>First-run:</b> speaks on launch. Camera permission requested in audio. Tutorial mode usable without sight.",
    ], styles))

    s.append(PageBreak())

    s.append(Paragraph("Constraints & non-negotiables", styles["h1"]))
    s.extend(bullets([
        "<b>Offline-first.</b> No cloud calls without explicit user permission. Models, OCR, voices: all on-device.",
        "<b>Library updates only when online.</b> Object classes, OCR language packs, phone profiles — silent background pull on Wi-Fi.",
        "<b>Top 5–10 phones tuned, not 100.</b> Ship the phone-profile library lean for v1; the 100-phone library is a Phase 3 marketing asset.",
        "<b>APK distribution only.</b> No Play Store dependency. Self-update channel is mandatory.",
        "<b>Solo developer.</b> No feature ships if it doubles the maintenance surface for marginal user value.",
    ], styles))

    s.append(Paragraph("Self-update flow (the unglamorous critical path)", styles["h1"]))
    s.extend(bullets([
        "<b>First install:</b> sighted helper installs the APK once and grants 'Install unknown apps' permission to AURIGA. 30-second job, documented on a one-page helper sheet.",
        "<b>Update check:</b> when online, AURIGA pings the website manifest, compares versions, downloads new APK in background.",
        "<b>Audio confirm:</b> on next launch, AURIGA speaks: <i>\"AURIGA update available, version 1.x. Double-tap with two fingers to install, or swipe right to skip.\"</i>",
        "<b>System installer:</b> Android system installer fires; readable by TalkBack if enabled.",
        "<b>Models / data:</b> separate silent channel — no permission, no dialog, no user interaction.",
    ], styles))

    s.append(Paragraph("Explicitly deferred to Phase 2", styles["h1"]))
    s.extend(bullets([
        "Scene description (template-based or VLM-based).",
        "DrakoVoice / Piper natural-voice overhaul. Use Android system TTS in v1.",
        "GhostAnchor jitter fixes beyond the top 5–10 phone profiles.",
        "Document/book reading mode, currency recognition, face recognition, color & light detection, advanced haptics.",
    ], styles))

    s.append(Paragraph("Pilot rollout", styles["h1"]))
    data2 = [
        ["Order", "Partner", "Use them for"],
        ["1", "Inable", "Qualitative depth — weekly 30-min calls with 2–3 tech-literate users."],
        ["2", "KSB", "Quantitative signal — 10–15 users, opt-in offline-batched usage telemetry."],
        ["3", "Local school", "Longitudinal learning curve — productivity on day 1, day 7, day 30."],
    ]
    t2 = Table(data2, colWidths=[15 * mm, 35 * mm, 120 * mm])
    t2.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), INK),
        ("TEXTCOLOR", (0, 0), (-1, 0), white),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 9.5),
        ("ALIGN", (0, 0), (0, -1), "CENTER"),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [white, PANEL]),
        ("LINEBELOW", (0, 0), (-1, -1), 0.3, RULE),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]))
    s.append(t2)

    s.append(Paragraph("Division of labor", styles["h1"]))
    s.extend(bullets([
        "<b>Trae (on the laptop):</b> the Android app — camera, ML Kit object detection + OCR, announcement engine, GhostAnchor, in-app updater, all native code.",
        "<b>Replit Agent (on the web side):</b> version manifest endpoint, APK hosting, the sighted-helper install page, feedback function, anything Netlify.",
        "<b>You:</b> hold the line on the house style, the definition of done, and the build order. Cut anything that drifts from the brief.",
    ], styles))

    s.append(Paragraph("How to use this brief with Trae", styles["h1"]))
    s.append(Paragraph(
        "Paste the entire 'Mission', 'House style', 'Phase 1 — what ships', 'Build order', "
        "'Definition of done', and 'Constraints' sections at the top of every Trae session as system "
        "context. Then ask for <b>one feature at a time, in build order</b>. Do not ask Trae to build "
        "everything at once. After each feature, push to a real device, run the blindfold test on that "
        "feature for an hour, then move to the next.",
        styles["body"]))

    doc.build(s, onFirstPage=page_decoration, onLaterPages=page_decoration)


# ---------- Brief 2: House Style cheat sheet ----------

def build_house_style(path):
    styles = build_styles()
    doc = SimpleDocTemplate(
        path, pagesize=A4,
        leftMargin=18 * mm, rightMargin=18 * mm,
        topMargin=22 * mm, bottomMargin=20 * mm,
    )
    doc.brief_tag = "HOUSE STYLE  v1.0"
    doc.brief_footer_left = "AURIGA Announcement Standard // Pin to wall"

    s = []
    s.append(Paragraph("AURIGA House Style", styles["title"]))
    s.append(Paragraph(
        "One rule. Pin it above your monitor. Apply it to every line of code that talks.",
        styles["subtitle"]))

    s.append(Paragraph("The rule", styles["h1"]))
    s.append(panel(
        "Every announcement follows: <b>&lt;what&gt; + &lt;distance&gt; + &lt;bearing&gt;</b>.<br/>"
        "If a feature can't say all three, it doesn't ship in that feature.",
        styles))

    s.append(Paragraph("Reference templates", styles["h1"]))
    s.append(Paragraph("Object find:", styles["h2"]))
    s.append(Paragraph("\"<b>{object}, {distance}, {bearing}.</b>\"", styles["mono"]))
    s.append(Paragraph("&rarr; \"Keys, 1.2 meters, 2 o'clock.\"", styles["body"]))

    s.append(Paragraph("OCR / text reading:", styles["h2"]))
    s.append(Paragraph("\"<b>{kind}, {distance}, {bearing}: '{text}'.</b>\"", styles["mono"]))
    s.append(Paragraph("&rarr; \"Sign, 80 centimeters, ahead: 'Tusker 100 bob'.\"", styles["body"]))

    s.append(Paragraph("Ambient / passive scan:", styles["h2"]))
    s.append(Paragraph("\"<b>{object}, {distance}, {bearing}.</b>\"", styles["mono"]))
    s.append(Paragraph("&rarr; \"Chair, half a meter, right.\"", styles["body"]))

    s.append(Paragraph("SkyShield / urgent:", styles["h2"]))
    s.append(Paragraph("\"<b>{hazard}, {distance}, {bearing}, stop.</b>\"", styles["mono"]))
    s.append(Paragraph("&rarr; \"Pole, 60 centimeters, ahead, stop.\"", styles["body"]))

    s.append(Paragraph("Vocabulary rules", styles["h1"]))
    s.extend(bullets([
        "<b>Distance:</b> use natural units. Under 1m &rarr; centimeters or 'half a meter'. 1m+ &rarr; meters with one decimal max. Never raw floats like '1.27 meters'.",
        "<b>Bearing:</b> clock positions for general use ('2 o'clock', 'ahead', 'left', 'right'). Reserve degrees for advanced/dev mode.",
        "<b>Object names:</b> short, common nouns. 'Keys' not 'key set'. 'Chair' not 'seating object'.",
        "<b>Order:</b> never reorder. Always <i>what</i>, then <i>distance</i>, then <i>bearing</i>. Predictability lets the user mentally pre-allocate attention.",
        "<b>Quiet mode:</b> announce only on <i>change</i>, not on every frame. Same object at same bearing &rarr; silent.",
        "<b>Failure speech:</b> if a layer fails, say what failed. 'Lens covered.' 'Too dark.' 'No GPS.' Never go silent.",
    ], styles))

    s.append(Paragraph("Forbidden patterns", styles["h1"]))
    s.extend(bullets([
        "Announcing without distance &rarr; <s>\"I see keys.\"</s>",
        "Announcing without bearing &rarr; <s>\"Keys, 1.2 meters.\"</s>",
        "Reordering &rarr; <s>\"At 2 o'clock, 1.2 meters, keys.\"</s>",
        "Verbose padding &rarr; <s>\"It looks like there might be keys roughly 1.2 meters away.\"</s>",
        "Silent failure &rarr; app stops talking with no explanation.",
    ], styles))

    s.append(Paragraph("Why this matters", styles["h1"]))
    s.append(Paragraph(
        "Every competitor — Seeing AI, Lookout, Envision, OrCam — describes <i>what</i> is there. "
        "Almost none reliably tell the user <i>where</i>. Your spatial engine can. The house style is "
        "what makes that capability <b>audible to the user in every interaction</b>. It is the moat.",
        styles["body"]))

    doc.build(s, onFirstPage=page_decoration, onLaterPages=page_decoration)


if __name__ == "__main__":
    build_project_brief("attached_assets/briefs/AURIGA_Project_Brief.pdf")
    build_house_style("attached_assets/briefs/AURIGA_House_Style.pdf")
    print("Generated:")
    print("  attached_assets/briefs/AURIGA_Project_Brief.pdf")
    print("  attached_assets/briefs/AURIGA_House_Style.pdf")
