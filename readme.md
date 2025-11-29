# GitHub Workflow Monitor

A lightweight command-line interface (CLI) tool written in Java that monitors GitHub Actions workflow runs in real-time. It provides a concise, one-line-per-event log of workflows, jobs, and steps as they occur.

[中文说明请见下方 (Chinese version below)](#github-工作流监控工具)

## Features

* **Real-time Monitoring**: Queries GitHub API regularly to report updates on Workflows, Jobs, and Steps.
* **State Persistence**: Automatically saves the timestamp of the last processed event upon exit. When restarted, it retrieves all events that occurred while the tool was offline, ensuring no data is lost.
* **Graceful Termination**: Handles `Ctrl+C` interruptions gracefully to save the current state before exiting.
* **Zero Dependencies**: Built entirely with standard Java 11+ libraries (`java.net.http`, `nio`, etc.). No Maven or Gradle setup required.
* **Visual Status**: Uses emoji indicators (🟢, 🔴, 🟡, ⚪) for quick status recognition.

## Prerequisites

* **Java Development Kit (JDK)**: Version 11 or higher.
* **GitHub Personal Access Token**: A classic token with `repo` scope permissions.

## Setting up the Target Repository

For the tool to report any events, the target repository **must** have a GitHub Actions workflow configured. If your repository doesn't have one, create a file named `.github/workflows/build.yml` in the repository root with the following content (example for a Java project):

```yaml
name: Java CI # <--- Customizable workflow name

on: [push]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          # Change '21' to your project's required JDK version (e.g., '17', '11', '8')
          java-version: '21' 
          distribution: 'temurin'
      - name: Build with Maven
        # Adjust the command below if you use Gradle (e.g., ./gradlew build)
        run: mvn -B package -DskipTests
````

Once you push this file to GitHub, any subsequent push will trigger a build, which this tool will detect and report.

## How to Build and Run

Since this tool has zero external dependencies, you can compile and run it directly using the `javac` and `java` commands.

### 1\. Compile the Code

Open your terminal in the project directory and run:

```bash
javac -d . src/GitHubWorkflowMonitor.java
```

### 2\. Run the Tool

Execute the tool by providing the target repository (`owner/repo`) and your GitHub token:

```bash
java GitHubWorkflowMonitor <owner/repo> <your_github_token>
```

**Example:**

```bash
java GitHubWorkflowMonitor google/gson ghp_AbCdEfG123456...
```

## Example Output

The tool outputs events in a tabular, easy-to-read format:

```text
🚀 Starting monitoring for google/gson
🕒 Monitoring events after: 14:30:00
-------------------------------------------------------------------------------------
TIME     | STATUS | TYPE | BRANCH       | SHA     | NAME
-------------------------------------------------------------------------------------
14:30:05 ⚪ QUEUE [Work] main        (7f2a1b) CI Build
14:30:10 🟡 RUN   [Work] main        (7f2a1b) CI Build
14:30:12 🟡 RUN   [Job ] main        (7f2a1b) Build Core
14:30:15 🟢 DONE  [Step] main        (7f2a1b) Checkout Code
14:30:45 🔴 FAIL  [Step] main        (7f2a1b) Run Tests
```

## How It Works

1.  **Polling**: The tool polls the GitHub API every 10-15 seconds for workflow updates.
2.  **State Management**: It creates a hidden file named `.gh_monitor_state` in the current directory. This file stores the timestamp of the last successfully processed event.
3.  **Resuming**: On startup, the tool reads this file. If found, it fetches all events that happened since that timestamp. If not found, it starts monitoring new events from the current time.

-----

# GitHub 工作流监控工具

这是一个使用纯 Java 编写的轻量级命令行 (CLI) 工具，用于实时监控 GitHub Actions 的运行状态。它能够以简洁的“每行一条事件”的格式，报告工作流（Workflow）、任务（Job）和步骤（Step）的状态更新。

## 核心功能

* **实时监控**：定期查询 GitHub API，报告从排队、运行到完成的所有状态变化。
* **断点续传**：程序退出时会自动保存进度。当你再次启动时，它会自动补录在程序停止期间发生的所有历史事件，确保数据不丢失。
* **优雅终止**：支持 `Ctrl+C` 中断操作，程序会在安全保存当前状态后优雅退出。
* **零依赖**：完全基于 Java 11+ 标准库开发（使用了 `HttpClient` 和 NIO），无需 Maven 或 Gradle 配置，下载源码即可运行。
* **可视化输出**：使用 Emoji 图标 (🟢, 🔴, 🟡, ⚪) 直观展示构建结果。

## 前置要求

* **Java Development Kit (JDK)**: 版本 11 或更高。
* **GitHub 个人访问令牌 (Token)**: 需要申请一个拥有 `repo` 权限的 Classic Token。

## 目标仓库配置指南

为了让监控工具能捕捉到事件，被监控的目标仓库**必须**配置了 GitHub Actions 工作流。如果你的仓库尚未配置，请在项目根目录下创建文件 `.github/workflows/build.yml`，并填入以下内容（以 Java 项目为例）：

```yaml
name: Java CI # <--- 你可以自定义工作流名称

on: [push] # 监听 Push 事件

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          # 请根据项目实际情况修改 JDK 版本 (如 '17', '11', '8')
          java-version: '21' 
          distribution: 'temurin'
      - name: Build with Maven
        # 如果使用 Gradle，请调整为相应命令 (如 ./gradlew build)
        run: mvn -B package -DskipTests
```

将此文件推送到 GitHub 后，后续的每一次代码提交都会自动触发构建，监控工具即可实时捕获这些状态。

## 编译与运行指南

由于本工具没有任何第三方依赖，你可以直接使用 JDK 自带的命令来编译和运行。

### 1\. 编译代码

在项目根目录下打开终端，执行以下命令：

```bash
javac -d . src/GitHubWorkflowMonitor.java
```

### 2\. 运行工具

运行命令时，需要提供目标仓库（格式为 `用户名/仓库名`）和你的 GitHub Token：

```bash
java GitHubWorkflowMonitor <用户名/仓库名> <你的GitHubToken>
```

**示例：**

```bash
java GitHubWorkflowMonitor google/gson ghp_AbCdEfG123456...
```

## 输出示例

工具会以表格形式输出清晰的日志信息：

```text
🚀 Starting monitoring for google/gson
🕒 Monitoring events after: 14:30:00
-------------------------------------------------------------------------------------
TIME     | STATUS | TYPE | BRANCH       | SHA     | NAME
-------------------------------------------------------------------------------------
14:30:05 ⚪ QUEUE [Work] main        (7f2a1b) CI Build
14:30:10 🟡 RUN   [Work] main        (7f2a1b) CI Build
14:30:12 🟡 RUN   [Job ] main        (7f2a1b) Build Core
14:30:15 🟢 DONE  [Step] main        (7f2a1b) Checkout Code
14:30:45 🔴 FAIL  [Step] main        (7f2a1b) Run Tests
```

## 工作原理

1.  **轮询机制**：工具每隔 10-15 秒向 GitHub API 发送请求，获取最新的工作流数据。
2.  **状态管理**：工具会在当前目录下生成一个名为 `.gh_monitor_state` 的隐藏文件，用于记录最后一次处理事件的时间戳。
3.  **历史补录**：程序启动时会读取该文件。如果文件存在，工具会检索自上次退出以来发生的所有事件并输出；如果文件不存在，则仅监控新发生的事件。

<!-- end list -->