#include "window.h"
#include <QApplication>
#include <QFile>
#include <QGroupBox>
#include <QScrollArea>
#include <QTextStream>
#include <QtTest>
class LayoutTests : public QObject {
    Q_OBJECT
private slots:
    void switchSettingsStayReadable() {
        QFile log("layout-debug.txt");
        log.open(QIODevice::WriteOnly | QIODevice::Text);
        QTextStream out(&log);
        out << "DIAG start\n"; out.flush();
        Window window(true);
        out << "DIAG window constructed\n"; out.flush();
        window.show();
        window.resize(QSize(640, 720).expandedTo(window.sizeHint()));
        QTest::qWait(50);
        out << "DIAG window shown, size=" << window.size().width() << "x" << window.size().height() << "\n"; out.flush();
        auto *status = window.findChild<QLabel *>("relayStatus");
        auto *group = window.findChild<QGroupBox *>("switchSettings");
        out << "DIAG status=" << (status ? "found" : "NULL") << " group=" << (group ? "found" : "NULL") << "\n"; out.flush();
        if (!(status && group)) { out << "DIAG FAIL find\n"; out.flush(); QFAIL("find"); }
        status->setText("Local capture failed: the selected network adapter could not be opened. Refresh adapters in Settings and inspect the capture error.");
        for (const char *name : {"switchIP", "switchMask", "switchGateway"}) {
            auto *value = window.findChild<QLabel *>(name);
            out << "DIAG value " << name << "=" << (value ? "found" : "NULL") << "\n"; out.flush();
            if (!value) { out << "DIAG FAIL value\n"; out.flush(); QFAIL("value"); }
            value->setText("255.255.255.255");
        }
        QTest::qWait(50);
        out << "DIAG group rect=" << group->rect().x() << "," << group->rect().y() << "," << group->rect().width() << "," << group->rect().height() << "\n"; out.flush();
        int i = 0;
        for (auto *label : group->findChildren<QLabel *>()) {
            QString t = label->text().left(50);
            int needH = label->fontMetrics().height();
            int needW = label->fontMetrics().horizontalAdvance(label->text());
            bool hOk = label->height() >= needH;
            bool wOk = label->width() >= needW;
            bool cOk = group->rect().contains(label->geometry());
            out << "DIAG label[" << i++ << "] '" << t << "' h=" << label->height() << ">=" << needH << ":" << hOk
                << " w=" << label->width() << ">=" << needW << ":" << wOk
                << " contained:" << cOk
                << " geom=" << label->geometry().x() << "," << label->geometry().y() << "," << label->geometry().width() << "," << label->geometry().height() << "\n";
            out.flush();
            if (!hOk || !wOk || !cOk) { out << "DIAG FAIL label\n"; out.flush(); QFAIL("label"); }
        }
        out << "DIAG labels done\n"; out.flush();
        for (auto *button : group->findChildren<QPushButton *>()) {
            bool hOk = button->height() >= button->sizeHint().height();
            bool cOk = group->rect().contains(button->geometry());
            out << "DIAG button '" << button->text() << "' h=" << button->height() << ">=" << button->sizeHint().height() << ":" << hOk
                << " contained:" << cOk << "\n";
            out.flush();
            if (!hOk || !cOk) { out << "DIAG FAIL button\n"; out.flush(); QFAIL("button"); }
        }
        out << "DIAG ALL PASS\n"; out.flush();
    }
};
// This console test has its own main, not a WinMain/Qt entry-point wrapper.
#ifdef main
#undef main
#endif
QTEST_MAIN(LayoutTests)
#include "layout-tests.moc"
