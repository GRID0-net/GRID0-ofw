#pragma once
#include "model.h"
#include <QProcess>
#include <QTimer>

class ProxyController : public QObject {
    Q_OBJECT
public:
    explicit ProxyController(QObject *parent = nullptr);
    bool busy() const { return process.state() != QProcess::NotRunning; }
    void start(const Preferences &, const QList<Adapter> &);
    void stop();
signals:
    void stateChanged();
    void message(const QString &);
    void lineReceived(const QString &);
private:
    QProcess process;
    QTimer killTimer;
    QByteArray pending;
    bool stopping = false;
};
