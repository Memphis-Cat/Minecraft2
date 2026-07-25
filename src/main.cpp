#include "App.h"

int WINAPI wWinMain(HINSTANCE instance,HINSTANCE,PWSTR,int showCommand){
    mc::App app;
    return app.Run(instance,showCommand);
}
