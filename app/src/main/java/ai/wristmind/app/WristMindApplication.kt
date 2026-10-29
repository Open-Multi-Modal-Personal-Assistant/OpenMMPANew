package ai.wristmind.app

import ai.wristmind.app.agent.WristMindKoogOrchestrator
import ai.wristmind.app.audio.WristSpeechController
import ai.wristmind.app.cloud.FirebaseAiCloudBridge
import ai.wristmind.app.data.memory.AppSearchLexicalIndex
import ai.wristmind.app.data.memory.HybridMemoryRepository
import ai.wristmind.app.data.memory.MyObjectBox
import ai.wristmind.app.edge.AdaptiveResidencyMonitor
import ai.wristmind.app.edge.EmbeddingGemmaEngine
import ai.wristmind.app.edge.LiteRtModelGalleryManager
import ai.wristmind.app.router.SystemOneDecisionEngine
import ai.wristmind.app.security.PromptInjectionShield
import ai.wristmind.app.skills.AndroidSkillExecutor
import ai.wristmind.app.skills.BetterHealthTrackerBridge
import ai.wristmind.app.vision.WatchCameraOcrManager
import android.app.Application
import android.content.Context
import android.util.Log
import io.objectbox.BoxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WristMindContainer(context: Context, val boxStore: BoxStore) {
    private val appContext = context.applicationContext

    val residencyMonitor = AdaptiveResidencyMonitor()
    val promptShield = PromptInjectionShield(appContext)
    val embeddingEngine = EmbeddingGemmaEngine(appContext, residencyMonitor)
    val galleryManager = LiteRtModelGalleryManager(appContext, residencyMonitor, embeddingEngine)
    val appSearchIndex = AppSearchLexicalIndex(appContext)
    val betterHealthBridge = BetterHealthTrackerBridge(appContext)

    val memoryRepository = HybridMemoryRepository(
        boxStore = boxStore,
        appSearchIndex = appSearchIndex,
        embeddingEngine = embeddingEngine,
        promptShield = promptShield
    )

    val systemOneRouter = SystemOneDecisionEngine(
        embeddingEngine = embeddingEngine,
        promptShield = promptShield
    )

    val skillExecutor = AndroidSkillExecutor(
        context = appContext,
        residencyMonitor = residencyMonitor,
        promptShield = promptShield,
        betterHealthBridge = betterHealthBridge,
        memoryRepositoryProvider = { memoryRepository }
    )

    val cloudBridge = FirebaseAiCloudBridge(appContext)

    val koogOrchestrator = WristMindKoogOrchestrator(
        memoryRepository = memoryRepository,
        systemOneRouter = systemOneRouter,
        galleryManager = galleryManager,
        cloudBridge = cloudBridge,
        skillExecutor = skillExecutor,
        promptShield = promptShield
    )

    val speechController = WristSpeechController(appContext)
    val cameraOcrManager = WatchCameraOcrManager()
}

class WristMindApplication : Application() {

    lateinit var container: WristMindContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val boxStore = MyObjectBox.builder()
            .androidContext(this)
            .build()

        if (BuildConfig.DEBUG) {
            runCatching {
                val adminClass = Class.forName("io.objectbox.android.Admin")
                val adminInstance = adminClass.getConstructor(BoxStore::class.java).newInstance(boxStore)
                val started = adminClass.getMethod("start", Context::class.java).invoke(adminInstance, this)
                Log.i("WristMind", "ObjectBox Admin browser started=$started")
            }
        }

        container = WristMindContainer(this, boxStore)
        registerComponentCallbacks(container.residencyMonitor)
        container.residencyMonitor.startMonitoring()
        container.galleryManager.refreshCatalogStatuses()

        appScope.launch {
            container.skillExecutor.stageAndDiscoverSkills()
            container.memoryRepository.initializeAndSeedDemoVault()
            container.embeddingEngine.tryLoadBestAvailableModel()
            container.cloudBridge.initializeIfConfigured()
        }
    }
}
