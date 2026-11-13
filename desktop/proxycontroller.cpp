#include "proxycontroller.h"
#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <QProcessEnvironment>
#include <QStandardPaths>

static QString findProxyScript() {
    const QString name = "zttestproxyv228.py";
    QDir dir(QCoreApplication::applicationDirPath());
    for (int i = 0; i < 5; i++) {
        for (const QString &rel : {name, "proxy/" + name, "desktop/proxy/" + name}) {
            const QString candidate = dir.filePath(rel);
            if (QFileInfo::exists(candidate)) return candidate;
        }
        if (!dir.cdUp()) break;
    }
    return {};
}

ProxyController::ProxyController(QObject *parent) : QObject(parent) {
    process.setProcessChannelMode(QProcess::MergedChannels);
    connect(&process, &QProcess::readyRead, this, [this] {
        pending += process.readAll();
        while (pending.contains('\n')) {
            auto pos = pending.indexOf('\n');
            emit lineReceived(QString::fromUtf8(pending.left(pos)).trimmed());
            pending.remove(0, pos + 1);
        }
        if (pending.size() > 65536) pending = pending.right(65536);
    });
    connect(&process, &QProcess::finished, this, [this](int code, QProcess::ExitStatus) {
        killTimer.stop();
        if (!pending.isEmpty()) { emit lineReceived(QString::fromUtf8(pending).trimmed()); pending.clear(); }
        emit stateChanged();
        emit message(stopping || code == 0 ? "Proxy stopped." : "The proxy exited. See the log below.");
        stopping = false;
    });
    connect(&process, &QProcess::errorOccurred, this, [this](QProcess::ProcessError e) {
        if (e == QProcess::FailedToStart) { emit message("Python was not found. Install Python 3 to run the proxy."); emit stateChanged(); }
    });
    killTimer.setSingleShot(true);
    connect(&killTimer, &QTimer::timeout, this, [this] { if (busy()) process.kill(); });
}

void ProxyController::start(const Preferences &p, const QList<Adapter> &adapters) {
    if (busy()) return;
    QString localIp;
    for (const auto &a : adapters) if (a.name == p.localInterface) localIp = a.ip;
    const QString ztIp = p.overlay(adapters).ip;
    if (localIp.isEmpty() || ztIp.isEmpty()) { emit message("Choose your local and ZeroTier adapters in Settings."); return; }
    const QString script = findProxyScript();
    if (script.isEmpty()) { emit message("The proxy script zttestproxyv228.py was not found next to the app."); return; }
    const QString keyFile = QFileInfo(script).dir().filePath("game_key.txt");
    if (!QFileInfo::exists(keyFile)) { emit message("Put game_key.txt next to the proxy script: " + QFileInfo(script).dir().path()); return; }
    QString python = QStandardPaths::findExecutable("python3");
    if (python.isEmpty()) python = QStandardPaths::findExecutable("python");
    if (python.isEmpty()) { emit message("Python was not found. Install Python 3 to run the proxy."); return; }
    auto env = QProcessEnvironment::systemEnvironment();
    env.insert("PHONE_IP", localIp);
    env.insert("ZT_IP", ztIp);
    env.insert("GAME_KEY_FILE", keyFile);
    env.insert("EVENTS", QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation) + "/proxy_events.jsonl");
    process.setProcessEnvironment(env);
    process.setWorkingDirectory(QFileInfo(script).dir().path());
    stopping = false;
    pending.clear();
    process.start(python, {script});
    emit stateChanged();
    emit message("Proxy starting…");
}

void ProxyController::stop() {
    if (!busy()) return;
    stopping = true;
    process.terminate();
    killTimer.start(3000);
    emit stateChanged();
}
