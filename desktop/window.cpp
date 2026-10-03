#include "window.h"
#include <QApplication>
#include <QClipboard>
#include <QCloseEvent>
#include <QCoreApplication>
#include <QDateTime>
#include <QDesktopServices>
#include <QJsonDocument>
#include <QJsonArray>
#include <QJsonObject>
#include <QNetworkRequest>
#include <QNetworkReply>
#include <QNetworkAccessManager>
#include <QDirIterator>
#include <QFileDialog>
#include <QFileInfo>
#include <QFontDatabase>
#include <QFormLayout>
#include <QFrame>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QMessageBox>
#include <QMenuBar>
#include <QPixmap>
#include <QProcess>
#include <QStandardPaths>
#include <QStyle>
#include <QTimer>
#include <QUrl>
#include <QGridLayout>
#include <QGuiApplication>
#include <QScrollArea>
#ifdef Q_OS_MACOS
#include <QtLiquidGlass/QtLiquidGlass.h>
#endif
#ifdef Q_OS_WIN
#include <QProcess>
// The release package puts everything in an app/ child folder. Create a
// shortcut in the parent folder so the user has a clean entry point.
static void ensureParentShortcut() {
    const QString appDir = QCoreApplication::applicationDirPath();
    const QString linkPath = QFileInfo(appDir).absolutePath() + "/GRID0-ofw.lnk";
    const QString target = QCoreApplication::applicationFilePath();
    // Use PowerShell to create the .lnk; MinGW's shobjidl.h is broken.
    QString ps = QString("powershell -NoProfile -Command \"$s = New-Object -ComObject WScript.Shell; "
                         "$l = $s.CreateShortcut('%1'); $l.TargetPath = '%2'; "
                         "$l.WorkingDirectory = '%3'; $l.Save()")
                     .arg(linkPath, target, appDir);
    QProcess::execute(ps);
}
#endif

static QLabel *text(const QString &s, QWidget *parent = nullptr) {
    auto *l = new QLabel(s, parent); l->setWordWrap(true); l->setTextFormat(Qt::PlainText); return l;
}
static void title(QLabel *l, int size) { auto f = l->font(); f.setPointSize(size); f.setWeight(QFont::DemiBold); l->setFont(f); }
// True when the release tag is actually newer, so the updater never offers
// a downgrade when the branch is ahead of the published releases.
static bool versionIsNewer(const QString &latest, const QString &current) {
    const auto lp = latest.split('.');
    const auto cp = current.split('.');
    for (int i = 0; i < qMax(lp.size(), cp.size()); i++) {
        int l = i < lp.size() ? lp[i].toInt() : 0;
        int c = i < cp.size() ? cp[i].toInt() : 0;
        if (l != c) return l > c;
    }
    return false;
}
#ifdef Q_OS_MACOS
static void addMacGlass(QWidget *surface, QtLiquidGlass::Material material, double radius) {
    // Tests and screenshots use Qt's offscreen platform, which deliberately
    // has no AppKit view to host the native effect.
    if (QGuiApplication::platformName() != "cocoa") return;
    QtLiquidGlass::Options options;
    options.cornerRadius = radius;
    options.appearance = QtLiquidGlass::AdaptiveAppearance::Auto;
    options.titlebarStyle = QtLiquidGlass::TitlebarStyle::Preserve;
    options.dragBehavior = QtLiquidGlass::WindowDragBehavior::Preserve;
    options.blendingMode = QtLiquidGlass::BlendingMode::WithinWindow;
    QtLiquidGlass::addGlassEffect(surface, material, options);
}
#endif
Window::Window(bool preview) : previewMode(preview) {
    setWindowTitle("GRID0-ofw"); resize(740, 720); setMinimumSize(640, 590);
    auto *appMenu = menuBar()->addMenu("GRID0-ofw");
    auto *preferencesAction = appMenu->addAction("Settings…");
    preferencesAction->setMenuRole(QAction::PreferencesRole); preferencesAction->setShortcut(QKeySequence::Preferences);
    connect(preferencesAction, &QAction::triggered, this, [this] { selectPage(1); });
    auto *quit = appMenu->addAction("Quit GRID0-ofw"); quit->setMenuRole(QAction::QuitRole); quit->setShortcut(QKeySequence::Quit);
    connect(quit, &QAction::triggered, this, &QWidget::close);
    if (!preview) preferences.load(settings);
    const QString bundled = bundledRelayPath();
    if (preferences.relayPath.isEmpty()) preferences.relayPath = bundled;
    auto *central = new QWidget; setCentralWidget(central);
    auto *layout = new QVBoxLayout(central); layout->setContentsMargins(24, 22, 24, 20); layout->setSpacing(16);
    auto *brand = new QHBoxLayout;
    brand->setSpacing(8);
    brand->addStretch();
    headerIcon = new QLabel;
    headerIcon->setPixmap(QPixmap(":/branding/windows-circle.png").scaled(44, 44, Qt::KeepAspectRatio, Qt::SmoothTransformation));
    headerIcon->setFixedSize(44, 44);
    headerIcon->setAlignment(Qt::AlignCenter);
    brand->addWidget(headerIcon);
    headerText = new QLabel;
    headerText->setAlignment(Qt::AlignLeft | Qt::AlignVCenter);
    brand->addWidget(headerText);
    auto *ofwLabel = new QLabel("ofw");
    QFont ofwFont; ofwFont.setPointSize(28); ofwFont.setWeight(QFont::Bold);
    ofwLabel->setFont(ofwFont);
    ofwLabel->setAlignment(Qt::AlignLeft | Qt::AlignVCenter);
    brand->addWidget(ofwLabel);
    headerOfw = ofwLabel;
    brand->addStretch();
    systemPalette = QApplication::palette();
    updateHeaderTheme();
    layout->addLayout(brand);
    auto *subtitle = text("Nintendo Switch LAN play over ZeroTier");
    subtitle->setAlignment(Qt::AlignCenter);
    layout->addWidget(subtitle);
    // Let QMacStyle draw its native rounded tabs. Document mode intentionally
    // uses square browser/editor tabs, inappropriate for this utility.
    tabs = new QTabWidget; layout->addWidget(tabs);

    auto *play = new QWidget; auto *playLayout = new QVBoxLayout(play); playLayout->setContentsMargins(16, 24, 16, 12); playLayout->setSpacing(8);
    auto *summary = new QFrame; summary->setObjectName("relaySummary");
    auto *summaryLayout = new QVBoxLayout(summary); summaryLayout->setContentsMargins(16, 14, 16, 14); summaryLayout->setSpacing(8);
    status = text("Ready to connect"); status->setObjectName("relayStatus"); title(status, 18); summaryLayout->addWidget(status);
    switchStatus = text("Start the relay to look for your Switch."); summaryLayout->addWidget(switchStatus);
    auto *actions = new QHBoxLayout;
    // Keep native control heights: forcing tall buttons makes QMacStyle fall
    // back to a rectangular bezel rather than the standard macOS button.
    start = new QPushButton("Start relay"); start->setAutoDefault(true); start->setDefault(true);
    stop = new QPushButton("Stop relay");
    manualMode = new QRadioButton("Manual"); autoMode = new QRadioButton("Automatic");
    manualMode->setObjectName("manualMode"); autoMode->setObjectName("autoMode");
    actions->addWidget(start); actions->addWidget(stop); actions->addStretch();
    actions->addWidget(manualMode); actions->addWidget(autoMode);
    summaryLayout->addLayout(actions); playLayout->addWidget(summary);
    auto *group = new QGroupBox("Enter these settings on your Switch"); auto *form = new QGridLayout(group);
    switchSettingsGroup = group;
    auto *autoGroup = new QGroupBox("Windows Hotspot Setup");
    auto *autoLayout = new QVBoxLayout(autoGroup);
    autoSettingsGroup = autoGroup;
    auto *dnsLabel = new QLabel("90DNS");
    dnsLabel->setToolTip("Pick whichever is closer to you. Only changes which server is tried first.");
    dnsUsFirst = new QRadioButton("US (207.246.121.77)"); dnsFrFirst = new QRadioButton("France (163.172.141.219)");
    for (auto *r : {dnsUsFirst, dnsFrFirst}) { auto f = r->font(); f.setPointSize(14); f.setWeight(QFont::DemiBold); r->setFont(f); }
    dnsUsFirst->setToolTip("Pick whichever is closer to you. Only changes which server is tried first.");
    dnsFrFirst->setToolTip("Pick whichever is closer to you. Only changes which server is tried first.");
    group->setObjectName("switchSettings");
    form->setSizeConstraint(QLayout::SetMinimumSize);
    group->setSizePolicy(QSizePolicy::Preferred, QSizePolicy::Minimum);
    form->setColumnStretch(1, 1);
    form->setColumnStretch(3, 1);
    form->setHorizontalSpacing(20);
    form->setContentsMargins(18, 22, 18, 18); form->setVerticalSpacing(14);
    address = text("—"); mask = text("—"); gatewayValue = text("—");
    dnsAmerica = text("207.246.121.77"); dnsEurope = text("163.172.141.219");
    address->setObjectName("switchIP"); mask->setObjectName("switchMask"); gatewayValue->setObjectName("switchGateway");
    dnsAmerica->setObjectName("switchDnsAmerica"); dnsEurope->setObjectName("switchDnsEurope");
    for (auto *l : {address, mask, gatewayValue, dnsAmerica, dnsEurope}) { l->setTextInteractionFlags(Qt::TextSelectableByMouse); title(l, 14); }
    int row = 0;
    for (auto pair : {qMakePair(QString("IP address"), address), qMakePair(QString("Subnet mask"), mask), qMakePair(QString("Gateway"), gatewayValue)}) {
        auto *label = new QLabel(pair.first);
        pair.second->setWordWrap(false);
        pair.second->setMinimumHeight(pair.second->fontMetrics().height() + 4);
        pair.second->setMinimumWidth(pair.second->fontMetrics().horizontalAdvance("255.255.255.255") + 12);
        form->addWidget(label, row, 0); form->addWidget(pair.second, row++, 1);
    }
    auto *dnsHeader = new QLabel();
    dnsHeader->setText("DNS (<a href=\"https://gbatemp.net/threads/90dns-dns-server-for-blocking-all-nintendo-servers.516234/\">90dns</a>, for an easier setup use 8.8.8.8)");
    dnsHeader->setTextFormat(Qt::RichText);
    dnsHeader->setTextInteractionFlags(Qt::TextBrowserInteraction);
    dnsHeader->setOpenExternalLinks(true);
    title(dnsHeader, 10);
    form->addWidget(dnsHeader, 0, 2, 1, 2);
    int dnsLine = 1;
    for (auto pair : {qMakePair(QString("America"), dnsAmerica), qMakePair(QString("Europe"), dnsEurope)}) {
        auto *label = new QLabel(pair.first);
        pair.second->setWordWrap(false);
        pair.second->setMinimumHeight(pair.second->fontMetrics().height() + 4);
        pair.second->setMinimumWidth(pair.second->fontMetrics().horizontalAdvance("255.255.255.255") + 12);
        form->addWidget(label, dnsLine, 2); form->addWidget(pair.second, dnsLine++, 3);
    }
    auto *copy = new QPushButton("Copy Switch settings");
    auto *dnsNotice = text("Set the closest as Primary, other as Secondary");
    dnsNotice->setWordWrap(false);
    auto *dnsHintRow = new QHBoxLayout;
    dnsHintRow->addWidget(copy);
    dnsHintRow->addStretch();
    dnsHintRow->addWidget(dnsNotice);
    form->addLayout(dnsHintRow, 3, 0, 1, 4);
    playLayout->addWidget(group);
#ifdef Q_OS_MACOS
    // A QFrame gives the native effect an independent host. QGroupBox uses a
    // shared Qt backing view, which would place the AppKit layer over its text.
    // Keep the window and Switch settings themselves in the native Qt style.
    addMacGlass(summary, QtLiquidGlass::Material::ClearGlass, 16.0);
#endif
    connect(copy, &QPushButton::clicked, this, [this] {
        QApplication::clipboard()->setText("IP address: " + address->text() + "\nSubnet mask: " + mask->text() + "\nGateway: " + gatewayValue->text() + "\nAmerica DNS: " + dnsAmerica->text() + "\nEurope DNS: " + dnsEurope->text());
    });
    settingsHint = text("After changing network settings, reconnect your Switch and restart the game before entering LAN mode.");
    playLayout->addWidget(settingsHint);
    dhcpHint = text("Connect your Switch to this PC's mobile hotspot with Automatic settings.");
    auto *hotspotGrid = new QGridLayout;
    auto *hotspotName = new QLabel("PC Hotspot");
    hotspotState = new QLabel("OFF"); title(hotspotState, 14);
    hotspotDot = new QLabel("●"); title(hotspotDot, 20);
    auto *hotspotStateRow = new QHBoxLayout;
    hotspotStateRow->addWidget(hotspotState);
    hotspotStateRow->addWidget(hotspotDot);
    hotspotStateRow->addStretch();
    hotspotGrid->addWidget(hotspotName, 0, 0);
    hotspotGrid->addLayout(hotspotStateRow, 0, 1);
#ifdef Q_OS_WIN
    auto *fwName = new QLabel("DHCP Guard");
    fwState = new QLabel("OFF"); title(fwState, 14);
    fwDot = new QLabel("●"); title(fwDot, 20);
    auto *fwStateRow = new QHBoxLayout;
    fwStateRow->addWidget(fwState);
    fwStateRow->addWidget(fwDot);
    fwStateRow->addStretch();
    hotspotGrid->addWidget(fwName, 0, 2);
    hotspotGrid->addLayout(fwStateRow, 0, 3);
#endif
    auto *dnsOptionRow = new QHBoxLayout;
    dnsOptionRow->addWidget(dnsUsFirst);
    dnsOptionRow->addWidget(dnsFrFirst);
    dnsOptionRow->addStretch();
    hotspotGrid->addWidget(dnsLabel, 1, 0);
    hotspotGrid->addLayout(dnsOptionRow, 1, 1);
    hotspotHint = text("");
    { auto f = hotspotHint->font(); f.setPointSize(9); hotspotHint->setFont(f); }
#ifdef Q_OS_WIN
    hotspotSetup = new QPushButton("Set up PC hotspot…");
    hotspotSetup->setToolTip("Opens Windows' Mobile hotspot settings. Turn the hotspot on, then come back and the relay picks it up on its own.");
    connect(hotspotSetup, &QPushButton::clicked, this, [this] {
        QDesktopServices::openUrl(QUrl("ms-settings:network-mobilehotspot"));
        // The user flips the toggle in Settings; poll until the new adapter shows up.
        auto *timer = new QTimer(this);
        auto *tries = new int(0);
        connect(timer, &QTimer::timeout, this, [this, timer, tries] {
            refreshAdapters();
            bool found = false;
            for (const auto &a : adapters) if (a.hotspot && a.up) { found = true; break; }
            if (found || ++(*tries) >= 10) { timer->stop(); timer->deleteLater(); delete tries; }
        });
        timer->start(3000);
    });
#endif
    // Automatic mode gets its own boxed section like the manual one.
    autoLayout->addLayout(hotspotGrid);
    autoLayout->addWidget(hotspotHint);
#ifdef Q_OS_WIN
    autoLayout->addWidget(hotspotSetup, 0, Qt::AlignLeft);
#endif
    autoLayout->addWidget(dhcpHint);
    playLayout->addWidget(autoGroup);
    validation = text(""); playLayout->addWidget(validation);
    auto *configure = new QPushButton("Connection settings…"); playLayout->addWidget(configure, 0, Qt::AlignLeft);
    playLayout->addStretch();
    connect(configure, &QPushButton::clicked, this, [this] { selectPage(1, 0); });
    playLayout->setSizeConstraint(QLayout::SetMinimumSize);
    auto *playScroll = new QScrollArea; playScroll->setWidgetResizable(true);
    playScroll->setFrameShape(QFrame::NoFrame); playScroll->setWidget(play);
    tabs->addTab(playScroll, "Play");

    settingsTabs = new QTabWidget; tabs->addTab(settingsTabs, "Settings");
    configuration = new QWidget; auto *network = new QVBoxLayout(configuration); network->setContentsMargins(18, 24, 18, 16); network->setSpacing(18);
    network->addWidget(text("Choose your connection once. Your choices are saved for the next session."));
    auto *networkForm = new QFormLayout; networkForm->setVerticalSpacing(16);
    networkForm->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);
    local = new QComboBox; local->setObjectName("localAdapter"); overlay = new QComboBox; overlay->setObjectName("overlayAdapter");
    local->setSizeAdjustPolicy(QComboBox::AdjustToMinimumContentsLengthWithIcon); overlay->setSizeAdjustPolicy(QComboBox::AdjustToMinimumContentsLengthWithIcon);
    local->setMinimumContentsLength(24); overlay->setMinimumContentsLength(24);
    networkForm->addRow("Switch connection", local); networkForm->addRow("ZeroTier connection", overlay);
    gateway = new QLineEdit; gateway->setPlaceholderText("Automatic from ZeroTier subnet"); networkForm->addRow("Fake gateway", gateway); network->addLayout(networkForm);
    network->addWidget(text("Connect the computer and Switch to the same local network. Connect ZeroTier on the computer before selecting its adapter. The game subnet comes from that adapter."));
    refresh = new QPushButton("Refresh adapters"); network->addWidget(refresh, 0, Qt::AlignLeft);
    network->addWidget(text("The desktop relay supports one Switch on a /24 ZeroTier network. Check the selected adapter’s address against the ZeroTier app."));
#ifdef Q_OS_WIN
    network->addWidget(text("Install Npcap and ZeroTier before starting. For Automatic (DHCP) mode, turn on the PC mobile hotspot from the Play tab first."));
#endif
    // Replaced by checkDependencies() at startup; this is what previews and
    // screenshots show, so it must not be an empty button.
    requirements = text("Checking for ZeroTier and packet capture support…"); network->addWidget(requirements);
    setupRequirements = new QPushButton("Check required software"); setupRequirements->setEnabled(false);
    network->addWidget(setupRequirements, 0, Qt::AlignLeft);
    network->addStretch(); settingsTabs->addTab(configuration, "Connection");

    auto *advanced = new QWidget; auto *av = new QVBoxLayout(advanced); av->setContentsMargins(18, 20, 18, 12); av->setSpacing(12);
    av->addWidget(text("Troubleshooting & reports"));
    diagnostics = new QCheckBox("Detailed traffic diagnostics"); capture = new QCheckBox("Save packet captures for the next relay session"); discovery = new QCheckBox("Find the Switch automatically");
    av->addWidget(diagnostics); av->addWidget(capture); av->addWidget(discovery);
    auto *themeRow = new QHBoxLayout; themeRow->addWidget(new QLabel("Appearance:"));
    theme = new QComboBox; theme->addItems({"System", "Dark", "Light"}); theme->setCurrentIndex(preferences.theme);
    themeRow->addWidget(theme); themeRow->addStretch(); av->addLayout(themeRow);
    auto *updateRow = new QHBoxLayout; auto *checkUpdates = new QPushButton("Check for updates"); updateRow->addWidget(checkUpdates); updateRow->addStretch(); av->addLayout(updateRow);
    connect(checkUpdates, &QPushButton::clicked, this, [this] { checkForUpdates(); });
    av->addWidget(text("Packet captures include game payloads and network addresses. Reports stay on this computer until you choose to share them."));
    auto *binaryRow = new QHBoxLayout; executable = new QLineEdit; executable->setPlaceholderText("Bundled relay (recommended)"); executable->setClearButtonEnabled(true); auto *choose = new QPushButton("Choose…");
    binaryRow->addWidget(executable); binaryRow->addWidget(choose); av->addWidget(text("Relay executable")); av->addLayout(binaryRow);
    log = new QPlainTextEdit; log->setReadOnly(true); log->setMaximumBlockCount(2000); log->setPlaceholderText("The relay log will appear here when you start a session.");
    log->setFont(QFontDatabase::systemFont(QFontDatabase::FixedFont)); av->addWidget(log, 1);
    auto *reportButtons = new QHBoxLayout; auto *exportButton = new QPushButton("Export report…"); auto *openFolder = new QPushButton("Open reports folder");
    reportButtons->addWidget(exportButton); reportButtons->addWidget(openFolder); reportButtons->addStretch(); av->addLayout(reportButtons);
    settingsTabs->addTab(advanced, "Advanced");
    gateway->setText(preferences.gateway); executable->setText(preferences.relayPath == bundled ? QString() : preferences.relayPath);
    diagnostics->setChecked(preferences.diagnostics); capture->setChecked(preferences.capture); discovery->setChecked(preferences.discover);
    connect(theme, QOverload<int>::of(&QComboBox::currentIndexChanged), this, [this](int i) { preferences.theme = i; updateHeaderTheme(); save(); });
    connect(refresh, &QPushButton::clicked, this, &Window::refreshAdapters);
    connect(setupRequirements, &QPushButton::clicked, this, &Window::setupDependencies);
    connect(&dependencies, &DependencyInstaller::progress, requirements, &QLabel::setText);
    connect(&dependencies, &DependencyInstaller::completed, this, [this](const QString &message) {
        checkDependencies();
        QMessageBox::information(this, "Setup complete", message + "\n\nClick OK to restart the application.");

        QProcess::startDetached(QCoreApplication::applicationFilePath(), QCoreApplication::arguments());
        QCoreApplication::quit();
    });
    connect(&dependencies, &DependencyInstaller::failed, this, [this](const QString &message) {
        checkDependencies(); QMessageBox::warning(this, "Setup needs attention", message);
    });
    for (auto *combo : {local, overlay}) connect(combo, &QComboBox::currentIndexChanged, this, [this] { save(); });
    for (auto *edit : {gateway, executable}) connect(edit, &QLineEdit::textChanged, this, [this] { save(); });
    for (auto *check : {diagnostics, capture, discovery}) connect(check, &QCheckBox::toggled, this, [this] { save(); });
    manualMode->setChecked(!preferences.dhcp); autoMode->setChecked(preferences.dhcp);
    for (auto *mode : {manualMode, autoMode}) connect(mode, &QRadioButton::toggled, this, [this] {
        if (loading) return;
        preferences.dhcp = autoMode->isChecked();
        preferences.autoSelectLocalAdapter(adapters);
        const int li = local->findData(preferences.localInterface);
        if (li >= 0) local->setCurrentIndex(li);
        save();
    });
    dnsUsFirst->setChecked(!preferences.dnsFranceFirst); dnsFrFirst->setChecked(preferences.dnsFranceFirst);
    for (auto *dns : {dnsUsFirst, dnsFrFirst}) connect(dns, &QRadioButton::toggled, this, [this] { save(); });
    connect(choose, &QPushButton::clicked, this, [this] {
        if (relay.busy()) return;
        auto path = QFileDialog::getOpenFileName(this, "Choose relay executable", executable->text());
        if (!path.isEmpty()) executable->setText(path);
    });
    connect(&relay, &RelayController::stateChanged, choose, [this, choose] { choose->setEnabled(!relay.busy()); });
    connect(start, &QPushButton::clicked, this, [this] {
        if (previewMode) return;
        refreshAdapters(); if (!preferences.validate(adapters).isEmpty()) return;
        log->clear(); switchStatus->setText("Looking for your Switch…"); relay.start(preferences, adapters);
    });
    connect(stop, &QPushButton::clicked, &relay, &RelayController::stop);
    connect(&relay, &RelayController::lineReceived, log, &QPlainTextEdit::appendPlainText);
    // The relay's DHCP server reports "GRID0_DHCP <kind> <ip> <mac>" events in
    // its output; surface them as the Switch connection status.
    connect(&relay, &RelayController::lineReceived, this, [this](const QString &line) {
        int at = line.indexOf("GRID0_DHCP ");
        if (at < 0) return;
        QStringList parts = line.mid(at).split(' ', Qt::SkipEmptyParts);
        if (parts.size() < 4) return;
        const QString kind = parts[1], ip = parts[2], mac = parts[3];
        if (kind == "assigned") {
            switchStatus->setText("Switch connected with automatic settings: " + ip + " (" + mac + ")");
        } else if (kind == "offered") {
            switchStatus->setText("Offering " + ip + " to your Switch…");
        } else if (kind == "lost") {
            switchStatus->setText("Another DHCP server answered first (" + ip + "). Toggle the Switch's Wi-Fi off and on to retry.");
        }
    });
    connect(&relay, &RelayController::message, status, &QLabel::setText);
    connect(&relay, &RelayController::switchDetected, this, [this](const QString &s) { switchStatus->setText("Local console detected: " + s); });
    connect(&relay, &RelayController::stateChanged, this, [this] { updateState(); if (closing && !relay.busy()) close(); });
    connect(exportButton, &QPushButton::clicked, this, &Window::exportReport);
    connect(openFolder, &QPushButton::clicked, this, [] {
        QString dir = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation) + "/reports";
        QDir().mkpath(dir); QDesktopServices::openUrl(QUrl::fromLocalFile(dir));
    });
    loading = false; refreshAdapters();
    // Missing ZeroTier or Npcap is reported once the window is up, not silently
    // left in Settings: without them Start cannot work at all.
    if (!previewMode) QTimer::singleShot(0, this, [this] { checkDependencies(); promptForDependencies(); });
    // Check for updates on launch. Quiet: only asks if an update is actually available.
    if (!previewMode) QTimer::singleShot(2000, this, [this] { checkForUpdates(true); });
#ifdef Q_OS_WIN
    if (!previewMode) ensureParentShortcut();
#endif
}
void Window::updateHeaderTheme() {
    int t = preferences.theme;
    const bool systemDark = systemPalette.color(QPalette::Window).lightness() < 128;
    bool dark = t == 1 || (t == 0 && systemDark);
    if (t == 0) {
        qApp->setStyleSheet(QString());
        qApp->setPalette(systemPalette);
    } else if (dark) {
        // The stylesheets paint the main surfaces, but plain containers
        // (the Play scroll area, plain widgets and frames) fall back to the
        // palette. Pin the background roles too, or a dark system theme
        // leaks through when Light is picked.
        QPalette pal = systemPalette;
        pal.setColor(QPalette::Window, QColor(0x1e, 0x1e, 0x1e));
        pal.setColor(QPalette::Base, QColor(0x2d, 0x2d, 0x2d));
        pal.setColor(QPalette::WindowText, Qt::white);
        pal.setColor(QPalette::Text, Qt::white);
        pal.setColor(QPalette::ButtonText, Qt::white);
        qApp->setPalette(pal);
        qApp->setStyleSheet(
            "QMainWindow, QDialog { background-color: #1e1e1e; }"
            "QTabWidget::pane { background-color: #1e1e1e; }"
            
            "QLabel { color: #ffffff; }"
                        "QLineEdit, QTextEdit, QPlainTextEdit, QListView { background-color: #2d2d2d; color: #ffffff; border: 1px solid #555555; border-radius: 6px; padding: 4px; }"
            "QComboBox { background-color: #2d2d2d; color: #ffffff; border: 1px solid #555555; border-radius: 6px; padding: 4px 8px; }"
            "QComboBox QAbstractItemView { background-color: #2d2d2d; color: #ffffff; selection-background-color: #3a3a3a; border: 1px solid #555555; }"
            "QComboBox::drop-down { subcontrol-origin: padding; subcontrol-position: top right; width: 22px; border-left: 1px solid #555555; }"
            "QComboBox::down-arrow { image: url(:/branding/combo-arrow-white.png); width: 12px; height: 12px; }"
            "QPushButton { background-color: #3a3a3a; color: #ffffff; border: 1px solid #555555; border-radius: 6px; padding: 6px 14px; outline: none; }"
            "QPushButton:hover { background-color: #4a4a4a; }"
            "QPushButton:pressed { background-color: #2a2a2a; }"
            "QPushButton:disabled { background-color: #252525; color: #777777; border: 1px solid #444444; }"
            "QTabWidget::pane { border: 1px solid #555555; background-color: #1e1e1e; }"
            "QTabBar::tab { background-color: #1e1e1e; color: #aaaaaa; padding: 8px 16px; border-top-left-radius: 6px; border-top-right-radius: 6px; }"
            "QTabBar::tab:selected { background-color: #3a3a3a; color: #ffffff; }"
            "QGroupBox { color: #ffffff; border: 1px solid #555555; border-radius: 6px; margin-top: 12px; }"
            "QGroupBox::title { subcontrol-origin: margin; left: 8px; }"
            

            "QMenuBar, QMenu { background-color: #2d2d2d; color: #ffffff; }"
        );
    } else {
        QPalette pal = systemPalette;
        pal.setColor(QPalette::Window, QColor(0xf0, 0xf0, 0xf0));
        pal.setColor(QPalette::Base, Qt::white);
        pal.setColor(QPalette::WindowText, Qt::black);
        pal.setColor(QPalette::Text, Qt::black);
        pal.setColor(QPalette::ButtonText, Qt::black);
        qApp->setPalette(pal);
        qApp->setStyleSheet(
            "QMainWindow, QDialog { background-color: #f0f0f0; }"
            "QTabWidget::pane { background-color: #f0f0f0; }"
            
            "QLabel { color: #000000; }"
                        "QLineEdit, QTextEdit, QPlainTextEdit, QListView { background-color: #ffffff; color: #000000; border: 1px solid #aaaaaa; border-radius: 6px; padding: 4px; }"
            "QComboBox { background-color: #ffffff; color: #000000; border: 1px solid #aaaaaa; border-radius: 6px; padding: 4px 8px; }"
            "QComboBox QAbstractItemView { background-color: #ffffff; color: #000000; selection-background-color: #e0e0e0; border: 1px solid #aaaaaa; }"
            "QComboBox::drop-down { subcontrol-origin: padding; subcontrol-position: top right; width: 22px; border-left: 1px solid #aaaaaa; }"
            "QComboBox::down-arrow { image: url(:/branding/combo-arrow-black.png); width: 12px; height: 12px; }"
            "QPushButton { background-color: #e0e0e0; color: #000000; border: 1px solid #aaaaaa; border-radius: 6px; padding: 6px 14px; }"
            "QPushButton:hover { background-color: #d0d0d0; }"
            "QPushButton:pressed { background-color: #c0c0c0; }"
            "QPushButton:disabled { background-color: #f5f5f5; color: #aaaaaa; border: 1px solid #cccccc; }"
            "QTabWidget::pane { border: 1px solid #aaaaaa; background-color: #f0f0f0; }"
            "QTabBar::tab { background-color: #f0f0f0; color: #666666; padding: 8px 16px; border-top-left-radius: 6px; border-top-right-radius: 6px; }"
            "QTabBar::tab:selected { background-color: #ffffff; color: #000000; }"
            "QGroupBox { color: #000000; border: 1px solid #aaaaaa; border-radius: 6px; margin-top: 12px; }"
            "QGroupBox::title { subcontrol-origin: margin; left: 8px; }"
            

            "QMenuBar, QMenu { background-color: #f0f0f0; color: #000000; }"
        );
    }
    const QString path = dark ? ":/branding/grid-text-dark.png" : ":/branding/grid-text-light.png";
    headerText->setPixmap(QPixmap(path).scaledToHeight(56, Qt::SmoothTransformation));
    headerOfw->setStyleSheet(dark ? "color: #cccccc; background: transparent;" : "color: #333333; background: transparent;");
}
void Window::checkForUpdates(bool quiet) {
    auto *manager = new QNetworkAccessManager(this);
    QNetworkRequest req(QUrl("https://api.github.com/repos/GRID0-net/GRID0-ofw/releases/latest"));
    req.setHeader(QNetworkRequest::UserAgentHeader, "GRID0-ofw");
    auto *reply = manager->get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply, quiet]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            if (!quiet) QMessageBox::warning(this, "Update check",
                QString("Could not check for updates: %1").arg(reply->errorString()));
            return;
        }
        auto doc = QJsonDocument::fromJson(reply->readAll());
        QString tag = doc["tag_name"].toString();
        if (tag.isEmpty()) {
            if (!quiet) QMessageBox::warning(this, "Update check", "Could not parse release info.");
            return;
        }
        QString current = QString::fromLatin1(LANPLAY_VERSION);
        QString currentTag = current.section('-', 1);
        QString latestTag = tag.startsWith('v') ? tag.mid(1) : tag;
        if (currentTag == latestTag) {
            if (!quiet) QMessageBox::information(this, "Update check", QString("You are on the latest version (%1).").arg(current));
            return;
        }
        if (!versionIsNewer(latestTag, currentTag)) {
            if (!quiet) QMessageBox::information(this, "Update check",
                QString("No newer release available (you have %1, latest release is %2).").arg(current, tag));
            return;
        }
        auto btn = QMessageBox::question(this, "Update available",
            QString("Version %1 is available (you have %2). Download and install?").arg(tag, current),
            QMessageBox::Yes | QMessageBox::No);
        if (btn != QMessageBox::Yes) return;
        QString assetName;
#ifdef Q_OS_WIN
        assetName = "Windows";
#elif defined(Q_OS_MACOS)
#ifdef Q_PROCESSOR_ARM_64
        assetName = "macOS-arm64";
#else
        assetName = "macOS-x64";
#endif
#else
        assetName = "AppImage";
#endif
        QString dlUrl;
        for (auto a : doc["assets"].toArray()) {
            QString name = a.toObject()["name"].toString();
            if (name.contains(assetName)) { dlUrl = a.toObject()["browser_download_url"].toString(); break; }
        }
        if (dlUrl.isEmpty()) {
            QDesktopServices::openUrl(QUrl("https://github.com/GRID0-net/GRID0-ofw/releases"));
            return;
        }
        QMessageBox::information(this, "Update", "Downloading update. The app will close and replace itself.");
        auto *dlManager = new QNetworkAccessManager(this);
        auto *dlReply = dlManager->get(QNetworkRequest(QUrl(dlUrl)));
        connect(dlReply, &QNetworkReply::finished, this, [this, dlReply, tag]() {
            dlReply->deleteLater();
            if (dlReply->error() != QNetworkReply::NoError) {
                QMessageBox::warning(this, "Update", "Download failed.");
                return;
            }
            QByteArray data = dlReply->readAll();
            if (data.size() < 1024 * 1024) {
                QMessageBox::warning(this, "Update", "Download failed (empty file).");
                return;
            }
            QString appDir = QCoreApplication::applicationDirPath();
            QString tmp = appDir + "/grid0-update.zip";
            QFile f(tmp);
            if (!f.open(QIODevice::WriteOnly) || f.write(data) < 0) {
                QMessageBox::warning(this, "Update", "Could not save update.");
                return;
            }
            f.close();
#ifdef Q_OS_WIN
            QString script = appDir + "/grid0-update.bat";
            QFile s(script);
            if (s.open(QIODevice::WriteOnly | QIODevice::Text)) {
                QTextStream ts(&s);
                ts << "@echo off\n";
                ts << "setlocal\n";
                ts << QString("set \"APPDIR=%1\"\n").arg(appDir);
                // Wait for this app to fully exit so none of its files are locked.
                ts << QString("powershell -NoProfile -Command \"while (Get-Process -Id %1 -ErrorAction SilentlyContinue) { Start-Sleep -Milliseconds 500 }\"\n")
                          .arg(QCoreApplication::applicationPid());
                // Delete everything in the app folder except the zip and this script.
                ts << "for %%F in (\"%APPDIR%\\*\") do (\n";
                ts << "  if /i not \"%%~nxF\"==\"grid0-update.zip\" if /i not \"%%~nxF\"==\"grid0-update.bat\" (\n";
                ts << "    if exist \"%%F\\\" (rmdir /s /q \"%%F\") else (del /f /q \"%%F\")\n";
                ts << "  )\n";
                ts << ")\n";
                // Extract the update in place.
                ts << "powershell -NoProfile -Command \"Expand-Archive -Path '%APPDIR%\\grid0-update.zip' -DestinationPath '%APPDIR%' -Force\"\n";
                ts << "if errorlevel 1 (\n";
                ts << "  echo Extraction failed. > \"%APPDIR%\\grid0-update-failed.txt\"\n";
                ts << "  exit /b 1\n";
                ts << ")\n";
                // The release zip nests the app under GRID0-ofw/app. Find the exe
                // wherever it landed and lift its folder contents up.
                ts << "powershell -NoProfile -Command \"$exe = Get-ChildItem -Path '%APPDIR%' -Recurse -Filter 'GRID0-ofw.exe' | Select-Object -First 1; if ($exe -and $exe.DirectoryName -ne '%APPDIR%') { Copy-Item ($exe.DirectoryName + '\\*') '%APPDIR%' -Recurse -Force; Remove-Item $exe.DirectoryName -Recurse -Force }\"\n";
                ts << "if exist \"%APPDIR%\\GRID0-ofw\" rmdir /s /q \"%APPDIR%\\GRID0-ofw\"\n";
                ts << "del \"%APPDIR%\\grid0-update.zip\"\n";
                // Replace the parent-folder shortcut too; the new app recreates it on launch.
                ts << "del \"%APPDIR%\\..\\GRID0-ofw.lnk\" 2>nul\n";
                ts << "start \"\" \"%APPDIR%\\GRID0-ofw.exe\"\n";
                ts << "del \"%~f0\"\n";
                s.close();
            }
            QProcess::startDetached("cmd.exe", {"/c", script});
#else
            QDesktopServices::openUrl(QUrl::fromLocalFile(tmp));
#endif
            qApp->quit();
        });
    });
}
void Window::selectPage(int page, int sub) { tabs->setCurrentIndex(page); settingsTabs->setCurrentIndex(sub); }
void Window::refreshAdapters() {
    if (relay.busy()) return;
    loading = true;
    adapters = preferences.refreshAdapters();
    for (const auto &a : adapters) {
        if (preferences.localInterface.isEmpty() && a.up && !a.overlay && (a.wifi || a.name == "en0")) preferences.localInterface = a.name;
        if (preferences.overlayInterface.isEmpty() && a.up && a.overlay) preferences.overlayInterface = a.name;
    }
    for (auto pair : {qMakePair(local, preferences.localInterface), qMakePair(overlay, preferences.overlayInterface)}) {
        pair.first->clear(); pair.first->addItem("Choose an adapter", QString());
        for (const auto &a : adapters) {
            const QString label = a.label.isEmpty() ? a.name : a.label;
            pair.first->addItem(label + " — " + a.ip + (a.up ? "" : " (offline)"), a.name);
            pair.first->setItemData(pair.first->count() - 1, a.name, Qt::ToolTipRole);
        }
        int index = pair.first->findData(pair.second);
        if (index < 0 && !pair.second.isEmpty()) { pair.first->addItem(pair.second + " (unavailable)", pair.second); index = pair.first->count() - 1; }
        pair.first->setCurrentIndex(qMax(0, index));
    }
    loading = false; save();
}
void Window::save() {
    if (loading || relay.busy()) return;
    preferences.localInterface = local->currentData().toString(); preferences.overlayInterface = overlay->currentData().toString();
    preferences.gateway = gateway->text().trimmed(); preferences.relayPath = executable->text().trimmed();
    const QString bundled = bundledRelayPath();
    if (preferences.relayPath.isEmpty()) preferences.relayPath = bundled;
    preferences.diagnostics = diagnostics->isChecked(); preferences.capture = capture->isChecked(); preferences.discover = discovery->isChecked();
    preferences.dhcp = autoMode->isChecked();
    preferences.dnsFranceFirst = dnsFrFirst->isChecked();
    if (!previewMode) {
        auto stored = preferences;
        if (stored.relayPath == bundled) stored.relayPath.clear(); // Moving the app must not leave a stale path.
        stored.save(settings);
    }
    updateState();
}
void Window::updateState() {
    auto a = preferences.overlay(adapters);
    address->setText(a.ip.isEmpty() ? "—" : a.ip); mask->setText(a.mask.isEmpty() ? "—" : a.mask);
    gatewayValue->setText(preferences.gateway.isEmpty() ? (a.gateway.isEmpty() ? "—" : a.gateway) : preferences.gateway);
    auto error = preferences.validate(adapters);
    validation->setText(error);
    validation->setVisible(!error.isEmpty());
    switchSettingsGroup->setVisible(!preferences.dhcp);
    autoSettingsGroup->setVisible(preferences.dhcp);
    settingsHint->setVisible(!preferences.dhcp);
    QString hotspotIp;
    for (const auto &ad : adapters) if (ad.hotspot && ad.up) { hotspotIp = ad.ip; break; }
    const bool hotspotOn = !hotspotIp.isEmpty();
    if (hotspotState && hotspotDot && hotspotHint) {
        if (preferences.dhcp) {
            hotspotState->setText(hotspotOn ? "ON" : "OFF");
            hotspotDot->setStyleSheet(hotspotOn ? "color: #27ae60;" : "color: #e74c3c;");
#ifdef Q_OS_WIN
            if (fwState && fwDot) {
                QProcess netsh;
                netsh.start("netsh", {"advfirewall", "firewall", "show", "rule", "name=GRID0 - block hotspot DHCP"});
                netsh.waitForFinished(5000);
                const bool fwOn = netsh.readAllStandardOutput().contains("GRID0 - block hotspot DHCP");
                fwState->setText(fwOn ? "ON" : "OFF");
                fwDot->setStyleSheet(fwOn ? "color: #27ae60;" : "color: #e74c3c;");
            }
            hotspotHint->setText(hotspotOn
                ? "Connect your Switch to it."
                : "Turn it on with the button below, then connect your Switch to it.");
#else
            hotspotHint->setText(hotspotOn
                ? "Hotspot network detected."
                : "Automatic mode works best with a PC-hosted hotspot.");
#endif
        }
    }
    if (hotspotSetup) hotspotSetup->setVisible(!hotspotOn);
    if (!relay.busy()) status->setText(error.isEmpty() ? "Ready to connect" : "Finish connection setup");
    start->setEnabled(!relay.busy() && error.isEmpty()); stop->setEnabled(relay.busy() && relay.state() != RelayController::Stopping);
    stop->setText(relay.state() == RelayController::Authorizing ? "Cancel" : "Stop relay");
    configuration->setEnabled(!relay.busy());
    for (QWidget *w : std::initializer_list<QWidget *>{diagnostics, capture, discovery, executable, manualMode, autoMode, dnsUsFirst, dnsFrFirst}) w->setEnabled(!relay.busy());
}
void Window::checkDependencies() {
    if (!requirements || !setupRequirements) return;
    const DependencyStatus state = dependencies.status();
#ifdef Q_OS_WIN
    if (state.winPcap) {
        requirements->setText("WinPcap is installed. Remove it before installing Npcap, then reopen GRID0-ofw.");
        setupRequirements->setText("Open Apps & Features…"); setupRequirements->setEnabled(true);
        return;
    }
    QStringList missing;
    if (!state.zeroTier) missing << "ZeroTier One";
    if (!state.npcap) missing << "Npcap";
    if (missing.isEmpty()) {
        requirements->setText("ZeroTier One and Npcap are ready.");
        setupRequirements->setText("Required software installed"); setupRequirements->setEnabled(false);
    } else {
        requirements->setText("Required software missing: " + missing.join(" and ") + ".");
        setupRequirements->setText("Download and install…"); setupRequirements->setEnabled(true);
    }
#elif defined(Q_OS_MACOS)
    if (state.zeroTier) {
        requirements->setText("ZeroTier is ready. macOS already includes libpcap.");
        setupRequirements->setText("ZeroTier installed"); setupRequirements->setEnabled(false);
    } else {
        requirements->setText("ZeroTier is required. macOS already includes libpcap.");
        setupRequirements->setText("Get ZeroTier for macOS…"); setupRequirements->setEnabled(true);
    }
#elif defined(Q_OS_LINUX)
    if (state.zeroTier && state.npcap) {
        requirements->setText("ZeroTier and libpcap are ready.");
        setupRequirements->setText("Required software installed"); setupRequirements->setEnabled(false);
    } else if (!state.zeroTier) {
        requirements->setText(state.npcap ? "ZeroTier is required." :
            "ZeroTier is required, and libpcap is missing: install your distribution's package "
            "(libpcap0.8 on Debian and Ubuntu, libpcap on Fedora and Arch).");
        setupRequirements->setText("Get ZeroTier for Linux…"); setupRequirements->setEnabled(true);
    } else {
        requirements->setText("libpcap is missing. Install your distribution's package "
            "(libpcap0.8 on Debian and Ubuntu, libpcap on Fedora and Arch), then reopen GRID0-ofw.");
        setupRequirements->setText("Install libpcap with your package manager"); setupRequirements->setEnabled(false);
    }
#else
    requirements->hide(); setupRequirements->hide();
#endif
}
void Window::promptForDependencies() {
    if (previewMode) return;
    const DependencyStatus state = dependencies.status();
#ifdef Q_OS_WIN
    if (state.winPcap) {
        QMessageBox box(QMessageBox::Warning, "GRID0-ofw needs Npcap",
            "WinPcap is installed on this PC. Npcap cannot be installed beside it, and GRID0-ofw "
            "needs Npcap to see your Switch's traffic.\n\nRemove WinPcap in Apps & Features, then reopen "
            "GRID0-ofw and it will offer the Npcap installer.", QMessageBox::NoButton, this);
        auto *open = box.addButton("Open Apps & Features…", QMessageBox::AcceptRole);
        box.addButton("Not now", QMessageBox::RejectRole);
        box.setDefaultButton(open);
        box.exec();
        if (box.clickedButton() == open) QDesktopServices::openUrl(QUrl("ms-settings:appsfeatures"));
        return;
    }
    if (state.zeroTier && state.npcap) return;
    QStringList missing;
    if (!state.zeroTier) missing << "ZeroTier One";
    if (!state.npcap) missing << "Npcap";
    const bool several = missing.size() > 1;
    QMessageBox box(QMessageBox::Warning, "Required software missing",
        "GRID0-ofw cannot start the relay without " + missing.join(" and ") + ".\n\n"
        "ZeroTier carries your Switch's LAN traffic to your friends, and Npcap lets the relay read and "
        "send that traffic on this PC.", QMessageBox::NoButton, this);
    box.setInformativeText("Installing downloads the official installer" + QString(several ? "s" : "") +
        ", asks Windows to validate " + (several ? "each signature" : "its signature") +
        ", and starts the installation. Npcap opens its own screen so you can approve its driver terms. "
        "Windows will ask for administrator permission.");
    auto *install = box.addButton("Install now…", QMessageBox::AcceptRole);
    box.addButton("Not now", QMessageBox::RejectRole);
    box.setDefaultButton(install);
    box.exec();
    if (box.clickedButton() == install) dependencies.installMissing();
#elif defined(Q_OS_MACOS)
    if (state.zeroTier) return;
    QMessageBox box(QMessageBox::Warning, "ZeroTier is required",
        "GRID0-ofw cannot start the relay without the ZeroTier client.\n\nZeroTier carries your "
        "Switch's LAN traffic to your friends. macOS already includes libpcap, so nothing else is needed.",
        QMessageBox::NoButton, this);
    auto *download = box.addButton("Get ZeroTier…", QMessageBox::AcceptRole);
    box.addButton("Not now", QMessageBox::RejectRole);
    box.setDefaultButton(download);
    box.exec();
    if (box.clickedButton() == download) QDesktopServices::openUrl(QUrl("https://www.zerotier.com/download/"));
#elif defined(Q_OS_LINUX)
    if (state.zeroTier && state.npcap) return;
    QStringList missing;
    if (!state.zeroTier) missing << "the ZeroTier client";
    if (!state.npcap) missing << "libpcap";
    QMessageBox box(QMessageBox::Warning, "Required software missing",
        "GRID0-ofw cannot start the relay without " + missing.join(" and ") + ".\n\n"
        "ZeroTier carries your Switch's LAN traffic to your friends, and libpcap lets the relay read and "
        "send that traffic on this computer.", QMessageBox::NoButton, this);
    if (!state.npcap)
        box.setInformativeText("libpcap comes from your distribution: install libpcap0.8 on Debian and "
            "Ubuntu, or libpcap on Fedora and Arch, then reopen GRID0-ofw.");
    QPushButton *download = state.zeroTier ? nullptr : box.addButton("Get ZeroTier…", QMessageBox::AcceptRole);
    auto *dismiss = box.addButton(download ? "Not now" : "OK", QMessageBox::RejectRole);
    box.setDefaultButton(download ? download : dismiss);
    box.exec();
    if (download && box.clickedButton() == download) QDesktopServices::openUrl(QUrl("https://www.zerotier.com/download/"));
#endif
}
void Window::setupDependencies() {
    const DependencyStatus state = dependencies.status();
#ifdef Q_OS_WIN
    if (state.winPcap) {
        if (QMessageBox::question(this, "Remove WinPcap", "GRID0-ofw needs Npcap and cannot install it beside WinPcap. Open Apps & Features to remove WinPcap now?") == QMessageBox::Yes) {
            QDesktopServices::openUrl(QUrl("ms-settings:appsfeatures"));
            QMessageBox::information(this, "Reopen GRID0-ofw", "After removing WinPcap, close and reopen GRID0-ofw. It will then offer the Npcap installer.");
        }
        return;
    }
    if (state.zeroTier && state.npcap) return;
    QStringList missing;
    if (!state.zeroTier) missing << "ZeroTier One";
    if (!state.npcap) missing << "Npcap";
    const QString prompt = "GRID0-ofw will download the official " + missing.join(" and ") +
        " installer" + (missing.size() == 1 ? QString() : "s") +
        ", validate each Windows signature, and start installation. Npcap opens its own installation screen so you can approve its driver terms. Windows administrator permission is required. Continue?";
    if (QMessageBox::question(this, "Install required software", prompt) == QMessageBox::Yes) dependencies.installMissing();
#elif defined(Q_OS_MACOS)
    if (!state.zeroTier && QMessageBox::question(this, "Get ZeroTier", "macOS already includes libpcap. Open ZeroTier’s official macOS download page?") == QMessageBox::Yes)
        QDesktopServices::openUrl(QUrl("https://www.zerotier.com/download/"));
#elif defined(Q_OS_LINUX)
    if (!state.zeroTier && QMessageBox::question(this, "Get ZeroTier", "libpcap comes from your distribution. Open ZeroTier’s official download page?") == QMessageBox::Yes)
        QDesktopServices::openUrl(QUrl("https://www.zerotier.com/download/"));
#endif
}
void Window::exportReport() {
    if (relay.busy()) { QMessageBox::information(this, "Finish the session first", "Stop the relay before exporting, so all packet captures are complete."); return; }
    QString source = relay.reportDirectory();
    if (source.isEmpty()) { QMessageBox::information(this, "No session report yet", "Start a relay session first. Earlier reports are available in the reports folder."); return; }
    QString parent = QFileDialog::getExistingDirectory(this, "Export report to folder"); if (parent.isEmpty()) return;
    QString dest = parent + "/GRID0-ofw-report-" + QFileInfo(source).fileName();
    if (QFileInfo::exists(dest) || !QDir().mkdir(dest)) { QMessageBox::warning(this, "Cannot export", "Choose a folder without an existing copy of this report."); return; }
    for (const auto &name : QDir(source).entryList(QDir::Files)) {
        if (!QFile::copy(source + "/" + name, dest + "/" + name)) {
            QMessageBox::warning(this, "Report incomplete", "Could not copy " + name + ". The original report is still available."); return;
        }
    }
    QDesktopServices::openUrl(QUrl::fromLocalFile(dest));
}
void Window::closeEvent(QCloseEvent *event) {
    if (relay.busy()) {
        closing = true; status->setText("Stopping the relay before closing…"); relay.stop(); event->ignore();
    } else event->accept();
}
