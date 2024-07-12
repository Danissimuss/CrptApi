package CrptApi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class CrptApi {

    private HttpClient httpClient; // для отправки запросов
    private ObjectMapper objectMapper; // для преобразования в JSON
    private ScheduledExecutorService scheduler; // планировщик
    private int limrequest; // лимит запросов
    private long interval; // интервал времени
    private BlockingQueue<Runnable> requestQue; // для создания очереди
    private AtomicInteger currentCount; // счетчик запросов
    private Object lock; // для синхронизации (чтобы блокировать доступ из разных потоков)

    // Задаем условия и инициализируем
    public CrptApi (TimeUnit timeUnit, int limrequest){
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.limrequest = limrequest;
        this.interval = timeUnit.toMillis(1);
        this.requestQue = new LinkedBlockingQueue<>();
        this.currentCount = new AtomicInteger();
        this.lock = new Object();

        startScheduler();
    }
    // Перобразуем запрос в JSON и отправляем
    private void sendDocReq(String signature, Object doc) throws IOException, InterruptedException {
        String jsonBody = objectMapper.writeValueAsString(doc); // преобразуем в JSON
        ObjectNode rootJson = objectMapper.createObjectNode(); // Создаем узел
        rootJson.put("document",jsonBody); // добавляем док в узел
        rootJson.put("signature", signature); // добавляем подпись
        String reqBody = objectMapper.writeValueAsString(rootJson); // преобразуем узел в строку
        HttpRequest request = HttpRequest.newBuilder() // Отправляем запрос (устанавливаем необходимые параметры)
                .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("Ответ:" + response);
    }

    // Создаем документ и регулируем очередь запросов
    private void docQue (String signature, Object doc) throws InterruptedException{
        Runnable reqTask = () ->{
            try {
                sendDocReq(signature,doc);
            } catch (IOException | InterruptedException e){
                e.printStackTrace();
            }
        };
        synchronized (lock){
            if (currentCount.get() < limrequest){
                currentCount.incrementAndGet();
                reqTask.run();
            }else {
                requestQue.offer(reqTask);
            }
        }
    }
    // Создаем ограничение для выполнения запросов
    private void startScheduler(){
        scheduler.scheduleAtFixedRate(() -> {
            synchronized (lock){
                currentCount.set(0);
                while (currentCount.get() < limrequest && !requestQue.isEmpty()){
                    requestQue.poll().run();
                }
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
    }

    public static void main(String[] args) throws InterruptedException {
        CrptApi api = new CrptApi(TimeUnit.MINUTES, 5);
        Object doc = new Object(); // меняем на свой документ
        String signature = "some-signature";
        api.docQue(signature, doc);
    }
}
