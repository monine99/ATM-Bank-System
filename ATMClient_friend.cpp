#include <iostream>
#include <string>
#include <cstring>
#include <winsock2.h>
#include <ws2tcpip.h>

#pragma comment(lib, "ws2_32.lib")

using namespace std;

const int PORT = 2525;
const int BUF_SIZE = 1024;

// 发送命令并打印日志
bool sendCmd(SOCKET fd, const string& cmd) {
    // 尝试发送带换行的版本（最通用）
    string sendStr = cmd + "\n";
    cout << "\n[客户端发送] " << sendStr;
    int ret = send(fd, sendStr.c_str(), sendStr.size(), 0);
    if (ret == SOCKET_ERROR) {
        cout << "发送失败！" << endl;
        return false;
    }
    return true;
}

// 接收响应并打印日志
string recvResp(SOCKET fd) {
    char buf[BUF_SIZE] = { 0 };
    cout << "[客户端等待响应...]" << endl;
    int ret = recv(fd, buf, BUF_SIZE - 1, 0);
    if (ret <= 0) {
        cout << "接收失败或连接断开！" << endl;
        return "";
    }
    string resp(buf);
    cout << "[服务器响应] " << resp;
    return resp;
}

int main() {
    WSADATA wsaData;
    WSAStartup(MAKEWORD(2, 2), &wsaData);

    string serverIP;
    cout << "请输入对方服务端IP：";
    cin >> serverIP;

    SOCKET fd = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in addr = { 0 };
    addr.sin_family = AF_INET;
    addr.sin_port = htons(PORT);
    inet_pton(AF_INET, serverIP.c_str(), &addr.sin_addr);

    cout << "\n正在连接服务器...";
    if (connect(fd, (sockaddr*)&addr, sizeof(addr)) < 0) {
        cout << "连接失败！" << endl;
        closesocket(fd);
        WSACleanup();
        system("pause");
        return 1;
    }
    cout << "连接成功！\n";

    cout << "\n===== ATM 客户端 =====\n";
    cout << "请输入卡号：";
    string card, pwd, cmd, resp;
    cin >> card;

    // 1. 发送 HELO
    sendCmd(fd, "HELO " + card);
    resp = recvResp(fd);
    if (resp.empty()) {
        closesocket(fd);
        WSACleanup();
        system("pause");
        return 1;
    }

    // 2. 发送 PASS
    cout << "\n请输入密码：";
    cin >> pwd;
    sendCmd(fd, "PASS " + pwd);
    resp = recvResp(fd);

    if (resp.find("525") == string::npos) {
        cout << "\n 登录失败！服务器响应：" << resp << endl;
        closesocket(fd);
        WSACleanup();
        system("pause");
        return 1;
    }
    cout << "\n 登录成功！" << endl;

    // 3. 主循环
    while (true) {
        cout << "\n输入命令(BALA/WDRA 金额/QUIT)：";
        cin >> cmd;

        if (cmd == "WDRA") {
            string money;
            cin >> money;
            cmd += " " + money;
        }

        sendCmd(fd, cmd);
        resp = recvResp(fd);

        if (resp.find("BYE") != string::npos) {
            cout << "\n已退出登录！" << endl;
            break;
        }
        if (resp.find("AMNT:") != string::npos) {
            cout << "余额：" << resp.substr(5);
        }
        else if (resp.find("525") != string::npos) {
            cout << "操作成功！";
        }
        else if (resp.find("401") != string::npos) {
            cout << "操作失败！";
        }
    }

    closesocket(fd);
    WSACleanup();
    cout << "\n按回车键退出...";
    cin.ignore();
    cin.get();
    return 0;
}