#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <windowsx.h>
#include <xinput.h>
#include <stdio.h>
#include <string.h>

#define EVENT_COUNT 12
#define EVENT_LENGTH 128

typedef DWORD (WINAPI *XInputGetStateProc)(DWORD, XINPUT_STATE *);

static HFONT font;
static XInputGetStateProc xinputGetState;
static char events[EVENT_COUNT][EVENT_LENGTH];
static int eventCount;
static POINT mousePosition;
static unsigned int mouseButtons;
static int mouseWheel;

static void addEvent(const char *format, WPARAM value) {
    char line[EVENT_LENGTH];
    _snprintf(line, sizeof(line) - 1, format, (unsigned int)value);
    line[sizeof(line) - 1] = '\0';

    if (eventCount < EVENT_COUNT) eventCount++;
    for (int i = eventCount - 1; i > 0; i--) {
        memcpy(events[i], events[i - 1], EVENT_LENGTH);
    }
    strncpy(events[0], line, EVENT_LENGTH - 1);
    events[0][EVENT_LENGTH - 1] = '\0';
}

static void loadXInput(void) {
    static const char *dllNames[] = {
        "xinput1_4.dll",
        "xinput1_3.dll",
        "xinput9_1_0.dll"
    };

    for (unsigned int i = 0; i < sizeof(dllNames) / sizeof(dllNames[0]); i++) {
        HMODULE module = LoadLibraryA(dllNames[i]);
        if (!module) continue;
        xinputGetState = (XInputGetStateProc)GetProcAddress(module, "XInputGetState");
        if (xinputGetState) return;
    }
}

static void appendButton(char *buffer, size_t size, WORD buttons, WORD mask, const char *name) {
    if (!(buttons & mask)) return;
    size_t used = strlen(buffer);
    if (used >= size - 1) return;
    _snprintf(buffer + used, size - used - 1, "%s%s", used ? " " : "", name);
    buffer[size - 1] = '\0';
}

static void drawTextLine(HDC dc, int x, int *y, COLORREF color, const char *text) {
    SetTextColor(dc, color);
    TextOutA(dc, x, *y, text, (int)strlen(text));
    *y += 24;
}

static void drawController(HDC dc, int x, int *y) {
    char line[256];
    if (!xinputGetState) {
        drawTextLine(dc, x, y, RGB(255, 120, 120), "XInput DLL not available");
        return;
    }

    int connected = 0;
    for (DWORD index = 0; index < XUSER_MAX_COUNT; index++) {
        XINPUT_STATE state;
        ZeroMemory(&state, sizeof(state));
        if (xinputGetState(index, &state) != ERROR_SUCCESS) continue;
        connected++;

        XINPUT_GAMEPAD *pad = &state.Gamepad;
        _snprintf(line, sizeof(line) - 1,
            "Controller %lu  packet=%lu", index + 1, state.dwPacketNumber);
        line[sizeof(line) - 1] = '\0';
        drawTextLine(dc, x, y, RGB(100, 210, 255), line);

        _snprintf(line, sizeof(line) - 1,
            "  LX %6d  LY %6d    RX %6d  RY %6d",
            pad->sThumbLX, pad->sThumbLY, pad->sThumbRX, pad->sThumbRY);
        line[sizeof(line) - 1] = '\0';
        drawTextLine(dc, x, y, RGB(235, 235, 235), line);

        _snprintf(line, sizeof(line) - 1,
            "  LT %3u  RT %3u", pad->bLeftTrigger, pad->bRightTrigger);
        line[sizeof(line) - 1] = '\0';
        drawTextLine(dc, x, y, RGB(235, 235, 235), line);

        char buttons[180] = "";
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_DPAD_UP, "UP");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_DPAD_DOWN, "DOWN");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_DPAD_LEFT, "LEFT");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_DPAD_RIGHT, "RIGHT");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_START, "START");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_BACK, "BACK");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_LEFT_THUMB, "L3");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_RIGHT_THUMB, "R3");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_LEFT_SHOULDER, "LB");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_RIGHT_SHOULDER, "RB");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_A, "A");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_B, "B");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_X, "X");
        appendButton(buttons, sizeof(buttons), pad->wButtons, XINPUT_GAMEPAD_Y, "Y");
        _snprintf(line, sizeof(line) - 1, "  Buttons: %s", buttons[0] ? buttons : "none");
        line[sizeof(line) - 1] = '\0';
        drawTextLine(dc, x, y, RGB(255, 220, 100), line);
        *y += 8;
    }

    if (!connected) {
        drawTextLine(dc, x, y, RGB(255, 180, 100), "No XInput controller connected");
    }
}

static void paintWindow(HWND window) {
    PAINTSTRUCT ps;
    HDC dc = BeginPaint(window, &ps);
    RECT client;
    GetClientRect(window, &client);
    FillRect(dc, &client, (HBRUSH)GetStockObject(BLACK_BRUSH));
    SelectObject(dc, font);
    SetBkMode(dc, TRANSPARENT);

    int y = 18;
    drawTextLine(dc, 20, &y, RGB(100, 210, 255), "Bannerlator Input Tester (C / Win32)");
    drawTextLine(dc, 20, &y, RGB(180, 180, 180),
        "Touch virtual controls and watch keyboard, mouse and XInput values.");
    y += 10;
    drawController(dc, 20, &y);

    char line[160];
    _snprintf(line, sizeof(line) - 1,
        "Mouse: x=%ld y=%ld buttons=0x%02X wheel=%d",
        mousePosition.x, mousePosition.y, mouseButtons, mouseWheel);
    line[sizeof(line) - 1] = '\0';
    drawTextLine(dc, 20, &y, RGB(160, 255, 160), line);
    y += 8;
    drawTextLine(dc, 20, &y, RGB(100, 210, 255), "Recent Win32 events:");
    for (int i = 0; i < eventCount; i++) {
        drawTextLine(dc, 38, &y, RGB(225, 225, 225), events[i]);
    }

    EndPaint(window, &ps);
}

static LRESULT CALLBACK windowProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
        case WM_CREATE:
            SetTimer(window, 1, 16, NULL);
            return 0;
        case WM_TIMER:
            InvalidateRect(window, NULL, FALSE);
            return 0;
        case WM_KEYDOWN:
        case WM_SYSKEYDOWN:
            addEvent("KEY DOWN  VK=0x%02X", wParam);
            InvalidateRect(window, NULL, FALSE);
            return 0;
        case WM_KEYUP:
        case WM_SYSKEYUP:
            addEvent("KEY UP    VK=0x%02X", wParam);
            InvalidateRect(window, NULL, FALSE);
            return 0;
        case WM_LBUTTONDOWN:
            mouseButtons |= 1;
            addEvent("MOUSE LEFT DOWN  flags=0x%02X", wParam);
            return 0;
        case WM_LBUTTONUP:
            mouseButtons &= ~1u;
            addEvent("MOUSE LEFT UP    flags=0x%02X", wParam);
            return 0;
        case WM_RBUTTONDOWN:
            mouseButtons |= 2;
            addEvent("MOUSE RIGHT DOWN flags=0x%02X", wParam);
            return 0;
        case WM_RBUTTONUP:
            mouseButtons &= ~2u;
            addEvent("MOUSE RIGHT UP   flags=0x%02X", wParam);
            return 0;
        case WM_MBUTTONDOWN:
            mouseButtons |= 4;
            addEvent("MOUSE MID DOWN   flags=0x%02X", wParam);
            return 0;
        case WM_MBUTTONUP:
            mouseButtons &= ~4u;
            addEvent("MOUSE MID UP     flags=0x%02X", wParam);
            return 0;
        case WM_MOUSEMOVE:
            mousePosition.x = GET_X_LPARAM(lParam);
            mousePosition.y = GET_Y_LPARAM(lParam);
            return 0;
        case WM_MOUSEWHEEL:
            mouseWheel += GET_WHEEL_DELTA_WPARAM(wParam);
            addEvent("MOUSE WHEEL delta=%d", (WPARAM)(short)GET_WHEEL_DELTA_WPARAM(wParam));
            return 0;
        case WM_PAINT:
            paintWindow(window);
            return 0;
        case WM_DESTROY:
            KillTimer(window, 1);
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProcA(window, message, wParam, lParam);
}

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR commandLine, int showCommand) {
    (void)previous;
    (void)commandLine;
    loadXInput();

    font = CreateFontA(
        20, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE,
        DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
        CLEARTYPE_QUALITY, FIXED_PITCH | FF_MODERN, "Consolas");

    WNDCLASSEXA windowClass;
    ZeroMemory(&windowClass, sizeof(windowClass));
    windowClass.cbSize = sizeof(windowClass);
    windowClass.lpfnWndProc = windowProc;
    windowClass.hInstance = instance;
    windowClass.hCursor = LoadCursor(NULL, IDC_ARROW);
    windowClass.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH);
    windowClass.lpszClassName = "BannerlatorInputTester";
    if (!RegisterClassExA(&windowClass)) return 1;

    HWND window = CreateWindowExA(
        0, windowClass.lpszClassName, "Bannerlator Input Tester",
        WS_OVERLAPPEDWINDOW | WS_VISIBLE,
        CW_USEDEFAULT, CW_USEDEFAULT, 920, 760,
        NULL, NULL, instance, NULL);
    if (!window) return 2;

    ShowWindow(window, showCommand);
    UpdateWindow(window);

    MSG message;
    while (GetMessageA(&message, NULL, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageA(&message);
    }

    if (font) DeleteObject(font);
    return (int)message.wParam;
}
