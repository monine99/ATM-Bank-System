import java.io.*;
import java.net.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * ATM 银行服务器端程序
 * 基于 TCP Socket 通信，支持多客户端并发访问
 *
 * 用法: java ATMServer [port]
 * 默认端口: 2525
 *
 * 通信协议 (RFC-20242024):
 *   HELO <userid>    → 500 AUTH REQUIRE
 *   PASS <passwd>    → 525 OK! / 401 ERROR!
 *   BALA             → AMNT:<amount>
 *   WDRA <amount>    → 525 OK! / 401 ERROR!
 *   DEPO <amount>    → 525 OK!           (新增: 存款)
 *   TRAN <card> <amt>→ 525 OK! / 401 ERROR! (新增: 转账)
 *   QUIT             → BYE
 */
public class ATMServer {

    private static final int DEFAULT_PORT = 2525;
    private static final DateTimeFormatter DT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 用户密码表: 卡号 -> 密码
    private static final Map<String, String> userPasswords = new ConcurrentHashMap<>();

    // 用户余额表: 卡号 -> BigDecimal
    private static final Map<String, BigDecimal> userBalances = new ConcurrentHashMap<>();

    // 服务器统计
    private static final AtomicInteger totalConnections = new AtomicInteger(0);
    private static final AtomicInteger activeConnections = new AtomicInteger(0);
    private static final AtomicInteger totalRequests = new AtomicInteger(0);

    // 交易日志写入器
    private static PrintWriter transactionLog;

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (port < 1024 || port > 65535) {
                    System.err.println("端口号需在 1024~65535 之间，使用默认端口 " + DEFAULT_PORT);
                    port = DEFAULT_PORT;
                }
            } catch (NumberFormatException e) {
                System.err.println("端口格式错误，使用默认端口 " + DEFAULT_PORT);
            }
        }

        loadUserData();

        if (userPasswords.isEmpty() || userBalances.isEmpty()) {
            System.err.println("==============================================");
            System.err.println("  致命错误: 无法加载用户数据，服务器拒绝启动");
            System.err.println("  请确保在项目目录下运行: cd ATM/ && java ATMServer");
            System.err.println("  当前目录: " + System.getProperty("user.dir"));
            System.err.println("  缺少文件: users.txt 或 balances.txt");
            System.err.println("==============================================");
            System.exit(1);
        }

        initTransactionLog();
        startMonitorThread();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("==============================================");
            System.out.println("  ATM 银行服务器已启动");
            System.out.println("  监听端口: " + port);
            System.out.println("  按 Ctrl+C 停止服务器");
            System.out.println("==============================================");

            ExecutorService pool = Executors.newCachedThreadPool();
            while (true) {
                Socket clientSocket = serverSocket.accept();
                totalConnections.incrementAndGet();
                pool.execute(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeTransactionLog();
        }
    }

    private static void loadUserData() {
        File userFile = resolveFile("users.txt");
        File balFile = resolveFile("balances.txt");

        System.out.println("[数据] 工作目录: " + System.getProperty("user.dir"));

        try (BufferedReader br = new BufferedReader(new FileReader(userFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2) {
                    userPasswords.put(parts[0], parts[1]);
                }
            }
            System.out.println("[数据] 已加载 " + userPasswords.size() + " 个用户账户");
        } catch (IOException e) {
            System.err.println("警告: 无法读取 users.txt (路径: " + userFile.getAbsolutePath() + ")");
        }

        try (BufferedReader br = new BufferedReader(new FileReader(balFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2) {
                    try {
                        userBalances.put(parts[0], new BigDecimal(parts[1]));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            System.out.println("[数据] 已加载 " + userBalances.size() + " 个余额记录");
        } catch (IOException e) {
            System.err.println("警告: 无法读取 balances.txt (路径: " + balFile.getAbsolutePath() + ")");
        }
    }

    /**
     * 解析文件：优先当前目录，其次 class 文件所在目录
     */
    private static File resolveFile(String filename) {
        File f = new File(filename);
        if (f.exists()) return f;

        // 尝试 class 文件所在目录
        try {
            File classDir = new File(ATMServer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParentFile();
            f = new File(classDir, filename);
            if (f.exists()) return f;
        } catch (Exception ignored) {
        }
        return new File(filename);
    }

    private static void initTransactionLog() {
        try {
            transactionLog = new PrintWriter(new FileWriter(resolveFile("transactions.log"), true), true);
        } catch (IOException e) {
            System.err.println("警告: 无法创建交易日志文件");
        }
    }

    private static void closeTransactionLog() {
        if (transactionLog != null) {
            transactionLog.close();
        }
    }

    /**
     * 启动监控线程，定期输出服务器状态
     */
    private static void startMonitorThread() {
        Thread monitor = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30000); // 每30秒输出一次
                    System.out.println("\n┌─────────────────────────────────────────┐");
                    System.out.println("│  服务器运行状态                           │");
                    System.out.println("├─────────────────────────────────────────┤");
                    System.out.printf("│  总连接数: %-30d │\n", totalConnections.get());
                    System.out.printf("│  当前活跃: %-30d │\n", activeConnections.get());
                    System.out.printf("│  请求总数: %-30d │\n", totalRequests.get());
                    System.out.printf("│  在线用户: %-30d │\n", activeConnections.get());
                    System.out.println("└─────────────────────────────────────────┘\n");
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }, "ServerMonitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private static synchronized void saveBalances() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(resolveFile("balances.txt")))) {
            for (Map.Entry<String, BigDecimal> entry : userBalances.entrySet()) {
                pw.printf("%s %.2f%n", entry.getKey(), entry.getValue());
            }
        } catch (IOException e) {
            System.err.println("保存 balances.txt 失败: " + e.getMessage());
        }
    }

    private static synchronized void writeTransactionLog(
            String cardNo, String operation, BigDecimal amount,
            String targetCard, String result) {
        if (transactionLog == null) return;
        String timestamp = LocalDateTime.now().format(DT_FORMAT);
        transactionLog.printf("[%s] 卡号=%s 操作=%s 金额=%s 对方=%s 结果=%s%n",
                timestamp, cardNo, operation,
                amount != null ? amount.toPlainString() : "-",
                targetCard != null ? targetCard : "-",
                result);
    }

    /**
     * 客户端处理线程
     */
    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private String currentCard = null;
        private boolean authenticated = false;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            activeConnections.incrementAndGet();

            try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {


                String line;
                while ((line = in.readLine()) != null) {
                    totalRequests.incrementAndGet();
                    System.out.printf("[REQ #%d] %s%n", totalRequests.get(), line);

                    String response = processCommand(line.trim());
                    out.println(response);

                    System.out.printf("[RES #%d] %s%n", totalRequests.get(), response);

                    if ("BYE".equals(response)) {
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("[" + currentCard + "] 通信异常: " + e.getMessage());
            } finally {
                activeConnections.decrementAndGet();
                try { socket.close(); } catch (IOException ignored) {}
                String user = (currentCard == null) ? "未知" : currentCard;
                System.out.println("[断开] " + user + "  (当前在线: " + activeConnections.get() + ")");
            }
        }

        private String processCommand(String cmd) {
            String[] parts = cmd.split("\\s+", 3);
            String command = parts[0].toUpperCase();

            switch (command) {
                case "HELO":
                    return handleHelo(parts);

                case "PASS":
                    return handlePass(parts);

                case "BALA":
                    return handleBala();

                case "WDRA":
                    return handleWdra(parts);

                case "DEPO":
                    return handleDepo(parts);

                case "TRAN":
                    return handleTran(parts);

                case "QUIT":
                    return handleQuit();

                default:
                    return "401 ERROR!";
            }
        }

        private String handleHelo(String[] parts) {
            if (parts.length < 2) return "401 ERROR!";
            currentCard = parts[1];
            if (userPasswords.containsKey(currentCard)) {
                authenticated = false;
                return "500 AUTH REQUIRE";
            } else {
                currentCard = null;
                return "401 ERROR!";
            }
        }

        private String handlePass(String[] parts) {
            if (currentCard == null || parts.length < 2) return "401 ERROR!";
            if (userPasswords.getOrDefault(currentCard, "").equals(parts[1])) {
                authenticated = true;
                writeTransactionLog(currentCard, "LOGIN", null, null, "SUCCESS");
                return "525 OK!";
            } else {
                writeTransactionLog(currentCard, "LOGIN", null, null, "FAIL");
                return "401 ERROR!";
            }
        }

        private String handleBala() {
            if (!authenticated) return "401 ERROR!";
            BigDecimal bal = userBalances.get(currentCard);
            if (bal == null) return "401 ERROR!";
            writeTransactionLog(currentCard, "BALA", bal, null, "OK");
            return String.format("AMNT:%s", bal.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }

        private String handleWdra(String[] parts) {
            if (!authenticated || parts.length < 2) return "401 ERROR!";

            BigDecimal amount;
            try {
                amount = new BigDecimal(parts[1]);
                if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                return "401 ERROR!";
            }

            BigDecimal balance = userBalances.get(currentCard);
            if (balance == null) return "401 ERROR!";

            if (balance.compareTo(amount) >= 0) {
                BigDecimal newBalance = balance.subtract(amount);
                userBalances.put(currentCard, newBalance);
                saveBalances();
                writeTransactionLog(currentCard, "WDRA", amount, null, "SUCCESS");
                return "525 OK! AMNT:" + newBalance.setScale(2, RoundingMode.HALF_UP).toPlainString();
            } else {
                writeTransactionLog(currentCard, "WDRA", amount, null, "FAIL:余额不足");
                return "401 ERROR!";
            }
        }

        private String handleDepo(String[] parts) {
            if (!authenticated || parts.length < 2) return "401 ERROR!";

            BigDecimal amount;
            try {
                amount = new BigDecimal(parts[1]);
                if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                return "401 ERROR!";
            }

            BigDecimal balance = userBalances.getOrDefault(currentCard, BigDecimal.ZERO);
            BigDecimal newBalance = balance.add(amount);
            userBalances.put(currentCard, newBalance);
            saveBalances();
            writeTransactionLog(currentCard, "DEPO", amount, null, "SUCCESS");
            return "525 OK! AMNT:" + newBalance.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }

        private String handleTran(String[] parts) {
            if (!authenticated || parts.length < 3) return "401 ERROR!";

            String targetCard = parts[1];
            BigDecimal amount;
            try {
                amount = new BigDecimal(parts[2]);
                if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                return "401 ERROR!";
            }

            // 不能给自己转账
            if (currentCard.equals(targetCard)) {
                return "401 ERROR!";
            }

            // 检查对方账户是否存在
            if (!userPasswords.containsKey(targetCard)) {
                writeTransactionLog(currentCard, "TRAN", amount, targetCard, "FAIL:目标不存在");
                return "401 ERROR!";
            }

            BigDecimal myBalance = userBalances.get(currentCard);
            if (myBalance == null || myBalance.compareTo(amount) < 0) {
                writeTransactionLog(currentCard, "TRAN", amount, targetCard, "FAIL:余额不足");
                return "401 ERROR!";
            }

            // 原子操作：扣自己，加对方
            BigDecimal newMyBalance = myBalance.subtract(amount);
            userBalances.put(currentCard, newMyBalance);
            BigDecimal targetBalance = userBalances.getOrDefault(targetCard, BigDecimal.ZERO);
            userBalances.put(targetCard, targetBalance.add(amount));
            saveBalances();
            writeTransactionLog(currentCard, "TRAN", amount, targetCard, "SUCCESS");
            return "525 OK! AMNT:" + newMyBalance.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }

        private String handleQuit() {
            writeTransactionLog(currentCard, "QUIT", null, null, "OK");
            authenticated = false;
            currentCard = null;
            return "BYE";
        }
    }
}
