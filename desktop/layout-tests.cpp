#include "window.h"
#include <QApplication>
#include <QGroupBox>
#include <QScrollArea>
#include <QTextDocument>
#include <QtTest>
class LayoutTests : public QObject {
    Q_OBJECT
private slots:
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
            // Rich text labels carry HTML markup; measure what is rendered.
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
