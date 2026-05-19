import java.io.*;
import java.net.*;

/**
 * ATM 客户端程序 v2.0
 * 通过 TCP Socket 与银行服务器通信，模拟 ATM 取款机操作
 *
 * 用法: java ATMClient [serverIP] [port]
 * 默认: 127.0.0.1:2525
 *
 * 功能:
 *   1. 查询余额    2. 取款
 *   3. 存款        4. 转账
 *   5. 退出
 */
public class ATMClient {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 2525;

    public static void main(String[] args) {
        String serverHost = DEFAULT_HOST;
        int serverPort = DEFAULT_PORT;

        if (args.length >= 1) serverHost = args[0];
        if (args.length >= 2) {
            try {
                serverPort = Integer.parseInt(args[1]);
                if (serverPort < 1024 || serverPort > 65535) {
                    System.err.println("端口号需在 1024~65535 之间，使用默认端口 " + DEFAULT_PORT);
                    serverPort = DEFAULT_PORT;
                }
            } catch (NumberFormatException e) {
                System.err.println("端口格式错误，使用默认端口 " + DEFAULT_PORT);
            }
        }

        try (Socket socket = new Socket(serverHost, serverPort);
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader console = new BufferedReader(
                     new InputStreamReader(System.in))) {

            in.readLine(); // 读取服务器欢迎信息
            System.out.println();
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║      欢迎使用 ATM 自助银行系统 v2.0      ║");
            System.out.println("╠══════════════════════════════════════════╣");
            System.out.printf("║  服务器: %-31s ║\n", serverHost + ":" + serverPort);
            System.out.println("╚══════════════════════════════════════════╝");

            // ========== 登录认证 ==========
            String cardNo = loginCardNo(console);
            if (cardNo == null) return;

            out.println("HELO " + cardNo);
            String response = in.readLine();
            if (response == null || !response.startsWith("500")) {
                System.out.println("\n  [错误] 卡号不存在，程序退出。");
                return;
            }

            String password = loginPassword(console);
            if (password == null) return;

            out.println("PASS " + password);
            response = in.readLine();
            if (!"525 OK!".equals(response)) {
                System.out.println("\n  [错误] 密码错误，程序退出。");
                return;
            }

            System.out.println("\n  [成功] 登录成功！欢迎您，" + cardNo + "\n");

            // ========== 主操作循环 ==========
            String lastBalance = "---";
            while (true) {
                showMenu(lastBalance);
                System.out.print("  请选择 [1-5]: ");

                String input = console.readLine();
                if (input == null) break;
                input = input.trim();

                String command = buildCommand(input, console);
                if (command == null) continue;   // 无效选项
                if (command.isEmpty()) break;    // 用户选择退出

                out.println(command);
                String resp = in.readLine();
                if (resp == null) break;

                lastBalance = processResponse(resp, command, lastBalance);

                if ("QUIT".equals(command)) break;
            }

            System.out.println("\n  感谢使用 ATM 自助银行系统，再见！\n");

        } catch (ConnectException e) {
            System.err.println("\n无法连接到服务器 " + serverHost + ":" + serverPort);
            System.err.println("请确认服务器已启动且地址端口正确。");
        } catch (IOException e) {
            System.err.println("通信异常: " + e.getMessage());
        }
    }

    // ==================== 认证阶段 ====================

    private static String loginCardNo(BufferedReader console) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            System.out.print("\n  请输入卡号: ");
            String input = console.readLine();
            if (input == null) return null;
            input = input.trim();
            if (!input.isEmpty() && input.matches("\\d+")) {
                return input;
            }
            System.out.println("  [提示] 卡号应为纯数字，请重新输入。");
        }
        System.out.println("\n  [错误] 输入次数过多，程序退出。");
        return null;
    }

    private static String loginPassword(BufferedReader console) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            String pwd = readPassword("  请输入密码: ");
            if (pwd == null) return null;
            if (!pwd.trim().isEmpty()) {
                return pwd.trim();
            }
            System.out.println("  [提示] 密码不能为空，请重新输入。");
        }
        System.out.println("\n  [错误] 输入次数过多，程序退出。");
        return null;
    }

    /**
     * 安全读取密码：优先使用 Console.readPassword()（原生不回显），
     * 不可用时回退到 BufferedReader。
     */
    private static String readPassword(String prompt) throws IOException {
        Console console = System.console();
        if (console != null) {
            char[] pwd = console.readPassword(prompt);
            return pwd != null ? new String(pwd) : null;
        }
        // 回退方案：IDE 终端不支持 Console，直接用 readLine
        System.out.print(prompt);
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        return br.readLine();
    }

    // ==================== 菜单 ====================

    private static void showMenu(String balance) {
        System.out.println("  ┌─────────────────────────────────────────┐");
        System.out.printf("  │  当前余额: ￥%-28s │\n", balance);
        System.out.println("  ├─────┬───────────────────────────────────┤");
        System.out.println("  │  1  │  查询余额                          │");
        System.out.println("  │  2  │  取款                              │");
        System.out.println("  │  3  │  存款                              │");
        System.out.println("  │  4  │  转账                              │");
        System.out.println("  │  5  │  退出                              │");
        System.out.println("  └─────┴───────────────────────────────────┘");
    }

    // ==================== 命令构造 ====================

    /**
     * 将菜单数字选项转换为对应的协议命令
     */
    private static String buildCommand(String choice, BufferedReader console) throws IOException {
        switch (choice) {
            case "1":
                return "BALA";

            case "2": {
                System.out.print("    请输入取款金额: ￥");
                String amt = console.readLine();
                if (amt == null || amt.trim().isEmpty()) return "";
                return "WDRA " + amt.trim();
            }

            case "3": {
                System.out.print("    请输入存款金额: ￥");
                String amt = console.readLine();
                if (amt == null || amt.trim().isEmpty()) return "";
                return "DEPO " + amt.trim();
            }

            case "4": {
                System.out.print("    请输入对方卡号: ");
                String target = console.readLine();
                if (target == null || target.trim().isEmpty()) return "";
                System.out.print("    请输入转账金额: ￥");
                String amt = console.readLine();
                if (amt == null || amt.trim().isEmpty()) return "";
                return "TRAN " + target.trim() + " " + amt.trim();
            }

            case "5":
                return "QUIT";

            default:
                System.out.println("  [提示] 无效选项，请输入 1-5。");
                return null;
        }
    }

    // ==================== 响应处理 ====================

    private static String processResponse(String resp, String sentCommand, String lastBalance) {
        if (resp == null) return lastBalance;

        if (resp.startsWith("AMNT:")) {
            String amt = resp.substring(5);
            System.out.println("  [余额] 当前余额: ￥" + amt);
            return amt;

        } else if (resp.startsWith("525 OK!")) {
            String op = sentCommand.split("\\s+")[0];
            String newBalance = lastBalance;

            // 提取附带的最新余额: "525 OK! AMNT:xxx"
            if (resp.contains("AMNT:")) {
                newBalance = resp.substring(resp.indexOf("AMNT:") + 5);
            }

            switch (op) {
                case "WDRA":
                    System.out.println("  [成功] 取款成功！请取走现金。");
                    break;
                case "DEPO":
                    System.out.println("  [成功] 存款成功！");
                    break;
                case "TRAN":
                    System.out.println("  [成功] 转账成功！");
                    break;
                default:
                    System.out.println("  [成功] 操作成功！");
            }
            return newBalance;

        } else if ("401 ERROR!".equals(resp)) {
            String op = sentCommand.split("\\s+")[0];
            switch (op) {
                case "WDRA":
                    System.out.println("  [失败] 取款失败，余额不足或金额无效。");
                    break;
                case "DEPO":
                    System.out.println("  [失败] 存款失败，金额无效。");
                    break;
                case "TRAN":
                    System.out.println("  [失败] 转账失败，请检查对方卡号和余额。");
                    break;
                default:
                    System.out.println("  [失败] 操作失败。");
            }
            return lastBalance;

        } else if ("BYE".equals(resp)) {
            return lastBalance;

        } else {
            System.out.println("  [响应] " + resp);
            return lastBalance;
        }
    }
}
