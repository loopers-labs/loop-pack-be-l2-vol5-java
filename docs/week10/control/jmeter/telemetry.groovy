import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import com.sun.management.OperatingSystemMXBean

SampleResult.setIgnore()
def cpu = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean).processCpuLoad
def actualReaders = props.get('control.readerGroup')?.getNumberOfThreads() ?: 0
def counts = [System.currentTimeMillis(), actualReaders, props.get('control.readCount').sum(),
              props.get('control.readErrors').sum(), props.get('control.waterSent').sum(),
              cpu < 0 ? '' : String.format(Locale.ROOT, '%.2f', 100 * cpu)]
def events = props.get('control.browseCounters')
counts.addAll(['listReads','brandReads','detailReads','revisitReads','mixedSent','hotViews','warmViews','longtailViews']
    .collect { events[it].sum() })
Files.writeString(props.get('control.telemetry'), counts.join(',') + '\n', StandardOpenOption.APPEND)
if (Files.exists(props.get('control.stop'))) {
    ctx.engine.askThreadsToStop()
}
