#pragma once

#include <string>
#include <algorithm>
#include <thread>
#include <functional>
#include <queue>
#include <cmath>
#include <dlfcn.h>
#include <unordered_set>
#include <condition_variable>
#include <android/choreographer.h>

#include "displayx_jni.hpp"
#include "view_transformation.hpp"
#include "window.hpp"
#include "cursor.hpp"

class DisplayX {
    private:
        enum class State {
            NONE,
            STOP,
            PAUSE,
            RESUME,
            CREATE_SURFACE,
            DESTROY_SURFACE,
            CHANGE_SURFACE,
            REQUEST_WINDOW_UPDATE
        };
        
        struct DisplayXLock {
           std::condition_variable cv;
           std::mutex mutex;
           
           std::unique_lock<std::mutex> lock() {
               return std::unique_lock<std::mutex>(mutex);
           }
           
           template<typename Predicate>
           void wait(std::unique_lock<std::mutex>& lock, Predicate pred) {
               cv.wait(lock, pred);
           }
           
           void notify() {
               cv.notify_all();
           }
        };
        
        class WindowQueue {
            private: 
                std::queue<Window *> mQueue;
                std::unordered_set<Window *> mSet;
            
            public:
                bool push(Window *window) {
                    if (mSet.insert(window).second) {
                        mQueue.push(window);
                        return true;
                    }
                    
                    return false;
                }
                
                Window *pop() {
                    if (mQueue.empty())
                        return nullptr;
                        
                    auto val = mQueue.front();
                    mQueue.pop();
                    mSet.erase(val);
                    
                    return val;
                }
                
                bool empty() {
                    return mQueue.empty();
                }
        };
        
        JNIEnv *env;
        int surfaceWidth;
        int surfaceHeight;
        AChoreographer *choreographer;
        ViewTransformation viewTransformation;
        DisplayXLock displayxLock;
        ASurfaceTransaction *windowTransaction;
        ASurfaceTransaction *cursorTransaction;
        std::queue<std::function<void()>> eventQueue;
        WindowQueue windowQueue;
        std::thread displayxThread;
        ANativeWindow *native_window;
        
        State state = State::NONE;
        bool cursorUpdate = false;
        bool paused = false;
        bool repostCursor = false;
        bool fullscreen = false;
        
        void renderingThreadLoop();
        static void onFrameCallback64(int64_t frameTimeNanos, void *data);
        void createRootWindowControl();
        void createRootCursorControl();
        void resizeRootWindow();
        void destroyRootWindowControl();
        void destroyRootCursorControl();
        void restoreControlState();
        
    public:
        WindowManager *windowManager;
        CursorManager *cursorManager;
        JNIXServer *xServer;
        JNICache *cache;
        
        bool cursorVisible = false;
        
        void start();
        void createSurface(ANativeWindow *window);
        void changeSurface(int width, int height);
        void destroySurface();
        void stop();
        void pause();
        void resume();
        
        void queueEvent(std::function<void()> func);
        void requestWindowUpdate(Window *window);
        void requestCursorUpdate();
        void updateCursorPosition();
        
        void createWindowControl(Window *window);
        void destroyWindowControl(Window *window);
        void mapWindow(Window *window);
        void unmapWindow(Window *window);
        void changeGeometry(Window *window, bool resized);
        void updateWindow(Window *window);
        void updateWindowDirect(Window *window);
        void reparentWindow(Window *window, Window *parent);
        void createCursor(Cursor *cursor);
        void destroyCursor(Cursor *cursor);
        void updateCursor(Window *window);
        void drawRootCursor();
        void toggleFullscreen();
};