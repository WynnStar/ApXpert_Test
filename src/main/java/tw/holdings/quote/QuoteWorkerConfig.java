package tw.holdings.quote;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class QuoteWorkerConfig {
    @Bean
    Clock quoteClock() {
        return Clock.system(QuoteRefreshPolicy.TAIPEI);
    }

    @Bean(destroyMethod="shutdownNow")
    ExecutorService quoteWarmupWorkers() {
        return new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2),job->{
            var thread=new Thread(job,"quote-warmup");thread.setDaemon(true);return thread;
        });
    }

    @Bean(destroyMethod = "shutdownNow")
    @org.springframework.context.annotation.Primary
    ExecutorService quoteWorkers() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(96), job -> {
                    Thread thread = new Thread(job, "quote-refresh");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
