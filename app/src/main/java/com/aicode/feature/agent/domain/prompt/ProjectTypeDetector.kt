package com.aicode.feature.agent.domain.prompt

/**
 * 识别到的项目类型。
 */
internal enum class ProjectType {
    ANDROID_GRADLE,
    FLUTTER,
    NODE,
    RUST,
    PYTHON,
    GO,
}

/**
 * 项目类型识别：只看工作区**根目录**的特征文件，判定项目类型并给出对应构建/测试约定。
 *
 * 纯函数、零 IO——传入根目录条目名集合即可，便于单测；实际扫描由调用方完成。
 * 只识别「有明确构建约定」的类型，识别不出时返回空（不臆断）。
 */
internal object ProjectTypeDetector {

    /** 根目录条目名 → 项目类型。多个命中时全部返回（保持稳定顺序）。 */
    private val MARKERS: List<Pair<ProjectType, (String) -> Boolean>> = listOf(
        ProjectType.ANDROID_GRADLE to { name: String ->
            name == "settings.gradle" || name == "settings.gradle.kts" ||
                name == "build.gradle" || name == "build.gradle.kts"
        },
        ProjectType.FLUTTER to { it == "pubspec.yaml" },
        ProjectType.NODE to { it == "package.json" },
        ProjectType.RUST to { it == "Cargo.toml" },
        ProjectType.PYTHON to { it == "pyproject.toml" || it == "requirements.txt" || it == "setup.py" },
        ProjectType.GO to { it == "go.mod" },
    )

    fun detect(rootEntryNames: Collection<String>): List<ProjectType> {
        val names = rootEntryNames.toSet()
        return MARKERS.filter { (_, match) -> names.any(match) }.map { it.first }
    }

    /** 项目类型对应的构建/测试约定，注入 system prompt 让 AI 知道该怎么编译验证。 */
    fun guidance(type: ProjectType): String = when (type) {
        ProjectType.ANDROID_GRADLE ->
            "这是 Android/Gradle 工程：构建用 `./gradlew <task>`（注意 flavor/变体命名），编译产物在 `build/` 下。"
        ProjectType.FLUTTER ->
            "这是 Flutter 工程：用 `flutter build` / `flutter test`，依赖在 `pubspec.yaml`。"
        ProjectType.NODE ->
            "这是 Node 工程：先看 `package.json` 的 scripts 决定 build/test 命令，依赖在 `node_modules/`。"
        ProjectType.RUST ->
            "这是 Rust 工程：用 `cargo build` / `cargo test`。"
        ProjectType.PYTHON ->
            "这是 Python 工程：优先用项目声明的虚拟环境/工具（pyproject.toml / requirements.txt）。"
        ProjectType.GO ->
            "这是 Go 工程：用 `go build ./...` / `go test ./...`。"
    }
}
