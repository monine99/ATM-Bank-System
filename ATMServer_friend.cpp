#include <iostream>
#include <string>
#include <map>
#include <fstream>
#include <sstream>
#include <thread>
#include <mutex>
#include <vector>
#include <cstring>
#include <stdexcept>
#include <winsock2.h>
#include <ws2tcpip.h>

#pragma comment(lib, "ws2_32.lib")

using namespace std;

const int DEFAULT_PORT = 2525;
map<string, string> userPasswords;
map<string, double> userBalances;
mutex userMutex, balanceMutex;

bool loadUserData() {
    ifstream fin("users.txt");
    if (!fin) { cerr << "找不到 users.txt" << endl; return false; }
    string line;
    while (getline(fin, line)) {
        if (line.empty()) continue;
        istringstream iss(line);
        string id, pwd;
        if (iss >> id >> pwd) userPasswords[id] = pwd;
    }
    fin.close();

    ifstream fin2("balances.txt");
    if (!fin2) { cerr << "找不到 balances.txt" << endl; return false; }
    while (getline(fin2, line)) {
        if (line.empty()) continue;
        istringstream iss(line);
        string id; double bal;
        if (iss >> id >> bal) userBalances[id] = bal;
    }
    fin2.close();
    cout << "用户数据加载完成！" << endl;
    return true;
}

bool saveBalances() {
    ofstream fout("balances.txt");
    for (auto& p : userBalances) fout << p.first << " " << p.second << endl;
    fout.close();
    return true;
}

string trim(const string& s) {
    size_t a = s.find_first_not_of(" \t\r\n"), b = s.find_last_not_of(" \t\r\n");
    if (a == string::npos) return "";
    return s.substr(a, b - a + 1);
}

vector<string> split(const string& s) {
    vector<string> r; istringstream i(s); string t;
    while (i >> t) r.push_back(t); return r;
}

string processCmd(const string& cmd, string& card, bool& auth) {
    try {
        auto p = split(cmd);
        if (p.empty()) return "401 ERROR!";
        string c = p[0];

        if (c == "HELO") {
            if (p.size() < 2) return "401 ERROR!";
            card = p[1];
            lock_guard<mutex> l(userMutex);
            if (userPasswords.count(card)) {
                auth = false;
                return "500 AUTH REQUIRE"; // 必须返回这个！
            }
            return "401 ERROR!";
        }

        if (c == "PASS") {
            if (card.empty() || p.size() < 2) return "401 ERROR!";
            lock_guard<mutex> l(userMutex);
            if (userPasswords[card] == p[1]) {
                auth = true;
                return "525 OK!";
            }
            return "401 ERROR!";
        }

        if (c == "BALA") {
            if (!auth) return "401 ERROR!";
            lock_guard<mutex> l(balanceMutex);
            char b[100]; sprintf_s(b, "AMNT:%.2f", userBalances[card]);
            return b;
        }

        if (c == "WDRA") {
            if (!auth || p.size() < 2) return "401 ERROR!";
            double m = stod(p[1]);
            if (m <= 0) return "401 ERROR!";
            lock_guard<mutex> l(balanceMutex);
            if (userBalances[card] >= m) {
                userBalances[card] -= m; saveBalances();
                return "525 OK!";
            }
            return "401 ERROR!";
        }

        if (c == "QUIT") {
            auth = false; card = "";
            return "BYE";
        }

        return "401 ERROR!";
    }
    catch (...) { return "401 ERROR!"; }
}

void handleClient(SOCKET clientSocket) {
    char buf[1024] = { 0 };
    string card, leftover;  // leftover 存放 recv 后不完整的半行数据
    bool auth = false;

    cout << "[服务端] 新客户端已连接" << endl;

    while (true) {
        ZeroMemory(buf, sizeof(buf));
        int recvLen = recv(clientSocket, buf, 1023, 0);

        if (recvLen <= 0) {
            break;
        }

        // 将新数据拼到上次未处理完的残部后面
        string data = leftover + string(buf, recvLen);
        leftover.clear();

        // 按 \n 逐行解析，最后一段不完整的留到下次再处理
        size_t pos = 0;
        while (true) {
            size_t nl = data.find('\n', pos);
            if (nl == string::npos) {
                leftover = data.substr(pos);  // 不足一行，暂存
                break;
            }
            string line = data.substr(pos, nl - pos);
            pos = nl + 1;

            string cmd = trim(line);
            if (cmd.empty()) continue;

            cout << "[服务端] 收到指令：" << cmd << endl;

            string resp = processCmd(cmd, card, auth) + "\n";
            send(clientSocket, resp.c_str(), (int)resp.size(), 0);
            cout << "[服务端] 发送响应：" << resp;

            if (resp == "BYE\n") {
                closesocket(clientSocket);
                cout << "[服务端] 客户端已断开连接" << endl;
                return;
            }
        }
    }

    cout << "[服务端] 客户端已断开连接" << endl;
    closesocket(clientSocket);
}

int main() {
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);

    if (!loadUserData()) {
        WSACleanup();
        return 1;
    }

    SOCKET serverSocket = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in serverAddr{ 0 };
    serverAddr.sin_family = AF_INET;
    serverAddr.sin_port = htons(DEFAULT_PORT);
    serverAddr.sin_addr.s_addr = INADDR_ANY;

    bind(serverSocket, (sockaddr*)&serverAddr, sizeof(serverAddr));
    listen(serverSocket, SOMAXCONN);

    cout << "=====================================" << endl;
    cout << "        ATM 服务器已启动 (端口:2525)        " << endl;
    cout << "=====================================" << endl;

    while (true) {
        SOCKET cSocket = accept(serverSocket, NULL, NULL);
        if (cSocket == INVALID_SOCKET) continue;
        thread(handleClient, cSocket).detach();
    }

    closesocket(serverSocket);
    WSACleanup();
    return 0;
}