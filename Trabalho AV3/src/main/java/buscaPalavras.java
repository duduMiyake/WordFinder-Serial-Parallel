import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jocl.*;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.ArrayList;

import static org.jocl.CL.*;

public class buscaPalavras {

    public static void main(String[] args) {
        String filePath = "Amostras/DonQuixote-388208.txt";
        String palavra = "y";
        List<Result> results = new ArrayList<>();

        try {
            String texto = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
            texto = texto.replaceAll("[\\p{Punct}]", "");

            // Executar e coletar resultados
            for (int i = 0; i < 3; i++) { // 3 amostras de cada execução
                results.add(runAnalysis(texto, palavra, "SerialCPU", buscaPalavras::contadorSerial));
                results.add(runAnalysis(texto, palavra, "ParallelCPU", (txt, word) -> contadorParaleloCPU(txt, word)));
                results.add(runAnalysis(texto, palavra, "ParallelGPU", (txt, word) -> contadorParaleloGPU(txt, word)));
            }

            // Gerar arquivos CSV
            generateCSV(results, "resultados.csv");

            // Criar gráfico
            SwingUtilities.invokeLater(() -> createChart(results));

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Result runAnalysis(String texto, String palavra, String method, Contador contador) {
        long tempoInicial = System.currentTimeMillis();
        int ocorrencias = contador.count(texto, palavra);
        long tempoFinal = System.currentTimeMillis();
        long tempoExecucao = tempoFinal - tempoInicial;
        System.out.println(method + ": " + ocorrencias + " ocorrências em " + tempoExecucao + " ms");
        return new Result(method, ocorrencias, tempoExecucao);
    }

    public static void generateCSV(List<Result> results, String fileName) {
        try (PrintWriter writer = new PrintWriter(new File(fileName))) {
            StringBuilder sb = new StringBuilder();
            sb.append("Metodo usado");
            sb.append(',');
            sb.append(" numero de ocorrencias");
            sb.append(',');
            sb.append(" tempo de execucao (ms)");
            sb.append('\n');

            for (Result result : results) {
                sb.append(result.method);
                sb.append(',');
                sb.append(result.ocorrencias);
                sb.append(',');
                sb.append(result.tempoExecucao);
                sb.append('\n');
            }

            writer.write(sb.toString());
            System.out.println("CSV file generated: " + fileName);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    public static void createChart(List<Result> results) {
        JFrame frame = new JFrame("Performance Analysis");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);

        ChartPanel chartPanel = new ChartPanel(results);
        frame.add(chartPanel);

        frame.setVisible(true);
    }

    public static int contadorSerial(String texto, String palavra) {
        int contador = 0;
        String[] palavraAtual = texto.split("\\s+");

        for (String s : palavraAtual) {
            if (s.equalsIgnoreCase(palavra)) {
                contador++;
            }
        }

        return contador;
    }

    public static int contadorParaleloCPU(String texto, String palavra) {
        String[] palavraAtual = texto.split("\\s+");
        int numThreads = Runtime.getRuntime().availableProcessors();
        // int numThreads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        int[] counts = new int[numThreads];
        int chunkSize = palavraAtual.length / numThreads;

        for (int i = 0; i < numThreads; i++) {
            final int comeco = i * chunkSize;
            final int fim = (i == numThreads - 1) ? palavraAtual.length : (i + 1) * chunkSize;
            final int threadIndex = i;
            executor.execute(() -> {
                int contadorLocal = 0;
                
                for (int j = comeco; j < fim; j++) {
                    if (palavraAtual[j].equalsIgnoreCase(palavra)) {
                        contadorLocal++;
                    }
                }

                counts[threadIndex] = contadorLocal;
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return Arrays.stream(counts).sum();
    }

    public static int contadorParaleloGPU(String texto, String palavra) {
        CL.setExceptionsEnabled(true);
        int platformIndex = 0;
        int deviceIndex = 0;

        cl_platform_id[] platforms = new cl_platform_id[1];
        clGetPlatformIDs(platforms.length, platforms, null);
        cl_platform_id platform = platforms[platformIndex];

        cl_device_id[] devices = new cl_device_id[1];
        clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices.length, devices, null);
        cl_device_id device = devices[deviceIndex];

        cl_context context = clCreateContext(null, 1, new cl_device_id[]{device}, null, null, null);
        cl_command_queue queue = clCreateCommandQueue(context, device, 0, null);

        String programSource =
                "__kernel void countOccurrences(__global const char *texto, __global const int *textoLength, __global const char *palavra, int palavraLength, __global int *result) {\n" +
                        "    int tid = get_global_id(0);\n" +
                        "    int localCount = 0;\n" +
                        "    int wordLength = palavraLength;\n" +
                        "    int textLength = textoLength[tid];\n" +
                        "    for (int i = 0; i <= textLength - wordLength; i++) {\n" +
                        "        int match = 1;\n" +
                        "        for (int j = 0; j < wordLength; j++) {\n" +
                        "            if (texto[tid * textLength + i + j] != palavra[j]) {\n" +
                        "                match = 0;\n" +
                        "                break;\n" +
                        "            }\n" +
                        "        }\n" +
                        "        localCount += match;\n" +
                        "    }\n" +
                        "    result[tid] = localCount;\n" +
                        "}\n";

        cl_program program = clCreateProgramWithSource(context, 1, new String[]{programSource}, null, null);
        clBuildProgram(program, 0, null, null, null, null);
        cl_kernel kernel = clCreateKernel(program, "countOccurrences", null);

        String[] words = texto.split("\\s+");
        int[] lengths = new int[words.length];
        ByteBuffer[] buffers = new ByteBuffer[words.length];

        byte[] palavraBytes = palavra.getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < words.length; i++) {
            byte[] wordBytes = words[i].getBytes(StandardCharsets.UTF_8);
            lengths[i] = wordBytes.length;
            buffers[i] = ByteBuffer.allocateDirect(lengths[i]).order(ByteOrder.nativeOrder());
            buffers[i].put(wordBytes);
            buffers[i].rewind();
        }

        cl_mem memTexto = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_char * texto.length(), Pointer.to(texto.getBytes(StandardCharsets.UTF_8)), null);
        cl_mem memLengths = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_int * lengths.length, Pointer.to(lengths), null);
        cl_mem memPalavra = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_char * palavraBytes.length, Pointer.to(palavraBytes), null);
        cl_mem memResult = clCreateBuffer(context, CL_MEM_READ_WRITE, Sizeof.cl_int * words.length, null, null);

        clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(memTexto));
        clSetKernelArg(kernel, 1, Sizeof.cl_mem, Pointer.to(memLengths));
        clSetKernelArg(kernel, 2, Sizeof.cl_mem, Pointer.to(memPalavra));
        clSetKernelArg(kernel, 3, Sizeof.cl_int, Pointer.to(new int[]{palavra.length()}));
        clSetKernelArg(kernel, 4, Sizeof.cl_mem, Pointer.to(memResult));

        long globalWorkSize[] = new long[]{words.length};
        clEnqueueNDRangeKernel(queue, kernel, 1, null, globalWorkSize, null, 0, null, null);

        int[] result = new int[words.length];
        clEnqueueReadBuffer(queue, memResult, CL_TRUE, 0, Sizeof.cl_int * words.length, Pointer.to(result), 0, null, null);

        clReleaseMemObject(memTexto);
        clReleaseMemObject(memLengths);
        clReleaseMemObject(memPalavra);
        clReleaseMemObject(memResult);
        clReleaseKernel(kernel);
        clReleaseProgram(program);
        clReleaseCommandQueue(queue);
        clReleaseContext(context);

        return Arrays.stream(result).sum();
    }

    @FunctionalInterface
    interface Contador {
        int count(String texto, String palavra);
    }

    static class Result {
        String method;
        int ocorrencias;
        long tempoExecucao;

        public Result(String method, int ocorrencias, long tempoExecucao) {
            this.method = method;
            this.ocorrencias = ocorrencias;
            this.tempoExecucao = tempoExecucao;
        }
    }

    static class ChartPanel extends JPanel {
        private final List<Result> results;

        public ChartPanel(List<Result> results) {
            this.results = results;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            int width = getWidth();
            int height = getHeight();
            int padding = 50;
            int labelPadding = 25;
            Color lineColor = new Color(44, 102, 230, 180);
            Color pointColor = new Color(100, 100, 100, 180);
            Stroke graphStroke = new BasicStroke(2f);
            int pointWidth = 4;
            int numberYDivisions = 10;

            // Criação de linhas de grade
            g2d.setColor(Color.white);
            g2d.fillRect(padding + labelPadding, padding, width - (2 * padding) - labelPadding, height - 2 * padding - labelPadding);
            g2d.setColor(Color.BLACK);

            double maxScore = getMaxScore();
            double minScore = getMinScore();

            for (int i = 0; i < numberYDivisions + 1; i++) {
                int x0 = padding + labelPadding;
                int x1 = pointWidth + padding + labelPadding;
                int y0 = height - ((i * (height - padding * 2 - labelPadding)) / numberYDivisions + padding + labelPadding);
                int y1 = y0;
                if (results.size() > 0) {
                    g2d.setColor(Color.BLACK);
                    String yLabel = ((int) ((minScore + (maxScore - minScore) * ((i * 1.0) / numberYDivisions)) * 100)) / 100 + "";
                    FontMetrics metrics = g2d.getFontMetrics();
                    int labelWidth = metrics.stringWidth(yLabel);
                    g2d.drawString(yLabel, x0 - labelWidth - 5, y0 + (metrics.getHeight() / 2) - 3);
                }
                g2d.setColor(Color.LIGHT_GRAY);
                g2d.drawLine(padding + labelPadding + 1 + pointWidth, y0, width - padding, y1);
                g2d.setColor(Color.BLACK);
                g2d.drawLine(x0, y0, x1, y1);
            }

            for (int i = 0; i < results.size(); i++) {
                if (results.size() > 1) {
                    int x0 = i * (width - padding * 2 - labelPadding) / (results.size() - 1) + padding + labelPadding;
                    int x1 = x0;
                    int y0 = height - padding - labelPadding;
                    int y1 = y0 - pointWidth;
                    if ((i % ((int) ((results.size() / 20.0)) + 1)) == 0) {
                        g2d.setColor(Color.BLACK);
                        String xLabel = results.get(i).method;
                        FontMetrics metrics = g2d.getFontMetrics();
                        int labelWidth = metrics.stringWidth(xLabel);
                        g2d.drawString(xLabel, x0 - labelWidth / 2, y0 + metrics.getHeight() + 3);
                    }
                    g2d.setColor(Color.LIGHT_GRAY);
                    g2d.drawLine(x0, height - padding - labelPadding - 1 - pointWidth, x1, padding);
                    g2d.setColor(Color.BLACK);
                    g2d.drawLine(x0, y0, x1, y1);
                }
            }

            // Criar eixo Y
            g2d.drawLine(padding + labelPadding, height - padding - labelPadding, padding + labelPadding, padding);
            // Criar eixo X
            g2d.drawLine(padding + labelPadding, height - padding - labelPadding, width - padding, height - padding - labelPadding);

            Stroke oldStroke = g2d.getStroke();
            g2d.setColor(lineColor);
            g2d.setStroke(graphStroke);

            double xScale = ((double) width - (2 * padding) - labelPadding) / (results.size() - 1);
            double yScale = ((double) height - 2 * padding - labelPadding) / (maxScore - minScore);

            List<Point> graphPoints = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                int x = (int) (i * xScale + padding + labelPadding);
                int y = (int) ((maxScore - results.get(i).tempoExecucao) * yScale + padding);
                graphPoints.add(new Point(x, y));
            }

            for (int i = 0; i < graphPoints.size() - 1; i++) {
                int x1 = graphPoints.get(i).x;
                int y1 = graphPoints.get(i).y;
                int x2 = graphPoints.get(i + 1).x;
                int y2 = graphPoints.get(i + 1).y;
                g2d.drawLine(x1, y1, x2, y2);
            }

            g2d.setStroke(oldStroke);
            g2d.setColor(pointColor);
            for (int i = 0; i < graphPoints.size(); i++) {
                int x = graphPoints.get(i).x - pointWidth / 2;
                int y = graphPoints.get(i).y - pointWidth / 2;
                int ovalW = pointWidth;
                int ovalH = pointWidth;
                g2d.fillOval(x, y, ovalW, ovalH);
            }
        }

        private double getMinScore() {
            double minScore = Double.MAX_VALUE;
            for (Result result : results) {
                minScore = Math.min(minScore, result.tempoExecucao);
            }
            return minScore;
        }

        private double getMaxScore() {
            double maxScore = Double.MIN_VALUE;
            for (Result result : results) {
                maxScore = Math.max(maxScore, result.tempoExecucao);
            }
            return maxScore;
        }
    }
}
