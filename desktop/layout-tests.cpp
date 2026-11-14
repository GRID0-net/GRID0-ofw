#include "window.h"
#include <QApplication>
#include <QGroupBox>
#include <QLabel>
#include <QPushButton>
#include <QRadioButton>
#include <QScrollArea>
#include <QTextDocument>
#include <QtTest>
class LayoutTests : public QObject {
    Q_OBJECT
private slots:
    void proxyModeLabelsAndState() {
        Window window(true);
        window.show();
        window.resize(QSize(640, 720).expandedTo(window.sizeHint()));
        QTest::qWait(50);
        QVERIFY(window.windowTitle().contains(QStringLiteral("3.0.0")));
        auto *manual = window.findChild<QRadioButton *>("manualMode");
        auto *automatic = window.findChild<QRadioButton *>("autoMode");
        auto *proxy = window.findChild<QRadioButton *>("proxyMode");
        QVERIFY(manual && automatic && proxy);
        QCOMPARE(manual->text(), QStringLiteral("Manual (the most difficult)"));
        QCOMPARE(automatic->text(), QStringLiteral("Automatic - DHCP (medium difficulty)"));
        QCOMPARE(proxy->text(), QStringLiteral("Automatic - Proxy (easiest)"));
        QPushButton *start = nullptr;
        for (auto *button : window.findChildren<QPushButton *>())
            if (button->text() == QStringLiteral("Start relay") || button->text() == QStringLiteral("Start proxy")) start = button;
        QVERIFY(start);
        auto *group = window.findChild<QGroupBox *>("switchSettings");
        QVERIFY(group);
        QVERIFY(group->isVisible());
        proxy->setChecked(true);
        QTest::qWait(50);
        QCOMPARE(start->text(), QStringLiteral("Start proxy"));
        QVERIFY(!group->isVisible());
        bool ztSeen = false;
        for (auto *label : window.findChildren<QLabel *>())
            if (label->text().startsWith(QStringLiteral("on GRID0 ZT:"))) { ztSeen = true; QVERIFY(label->isVisible()); }
        QVERIFY(ztSeen);
        manual->setChecked(true);
        QTest::qWait(50);
        QCOMPARE(start->text(), QStringLiteral("Start relay"));
        QVERIFY(group->isVisible());
    }
    void dhcpModeShowsAutoSettings() {
        Window window(true);
        window.show();
        window.resize(QSize(640, 720).expandedTo(window.sizeHint()));
        QTest::qWait(50);
        QGroupBox *switchGroup = nullptr, *autoGroup = nullptr;
        for (auto *group : window.findChildren<QGroupBox *>()) {
            if (group->title() == QStringLiteral("Enter these settings on your Switch")) switchGroup = group;
            if (group->title() == QStringLiteral("Windows Hotspot Setup")) autoGroup = group;
        }
        QVERIFY(switchGroup && autoGroup);
        auto *automatic = window.findChild<QRadioButton *>("autoMode");
        QVERIFY(automatic);
        QVERIFY(switchGroup->isVisible());
        QVERIFY(!autoGroup->isVisible());
        automatic->setChecked(true);
        QTest::qWait(50);
        QVERIFY(!switchGroup->isVisible());
        QVERIFY(autoGroup->isVisible());
        bool ztHidden = false;
        for (auto *label : window.findChildren<QLabel *>())
            if (label->text().startsWith(QStringLiteral("on GRID0 ZT:"))) ztHidden = !label->isVisible();
        QVERIFY(ztHidden);
    }
    void switchSettingsStayReadable() {
        Window window(true);
        window.show();
        // Size to fit the content: the panel grows as rows are added, so a
        // hardcoded height breaks every time the UI legitimately gets taller.
        window.resize(QSize(640, 720).expandedTo(window.sizeHint()));
        QTest::qWait(50);
        auto *status = window.findChild<QLabel *>("relayStatus");
        auto *group = window.findChild<QGroupBox *>("switchSettings");
        QVERIFY(status && group);
        status->setText("Local capture failed: the selected network adapter could not be opened. Refresh adapters in Settings and inspect the capture error.");
        for (const char *name : {"switchIP", "switchMask", "switchGateway"}) {
            auto *value = window.findChild<QLabel *>(name);
            QVERIFY(value);
            value->setText("255.255.255.255");
        }
        QTest::qWait(50);
        for (auto *label : group->findChildren<QLabel *>()) {
            // rich text labels carry html, measure what shows
            QString shown = label->text();
            if (label->textFormat() == Qt::RichText) {
                QTextDocument doc;
                doc.setHtml(shown);
                shown = doc.toPlainText();
            }
            QVERIFY2(label->height() >= label->fontMetrics().height(), qPrintable(label->text()));
            QVERIFY2(label->width() >= label->fontMetrics().horizontalAdvance(shown), qPrintable(label->text()));
            QVERIFY(group->rect().contains(label->geometry()));
        }
        for (auto *button : group->findChildren<QPushButton *>()) {
            QVERIFY(button->height() >= button->sizeHint().height());
            QVERIFY(group->rect().contains(button->geometry()));
        }
    }
};
// This console test has its own main, not a WinMain/Qt entry-point wrapper.
#ifdef main
#undef main
#endif
QTEST_MAIN(LayoutTests)
#include "layout-tests.moc"
