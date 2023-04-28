class SemaphoreSequential {
    private val s = kotlinx.coroutines.sync.Semaphore(1, 0)
    suspend fun acquire() {
        s.acquire()
    }

    fun release() {
        s.release()
    }
}