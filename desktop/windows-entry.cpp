#include <windows.h>
#include <cstdlib>

// Do not call `main`: Qt headers can rename the application's main to qMain,
// leaving MinGW's fallback main -> WinMain stub. Calling it loops forever.
extern int grid0OfwMain(int, char **);
int WINAPI WinMain(HINSTANCE, HINSTANCE, LPSTR, int) {
    return grid0OfwMain(__argc, __argv);
}
