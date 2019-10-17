package com.example.grpc;

import com.example.grpc.protos.GreetServiceGrpc;

import com.example.grpc.protos.Greeting;
import io.grpc.*;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

public class ServerApplication {
    private static final Logger logger = Logger.getLogger(ServerApplication.class.getName());
    private Server server;

    private final int port;
    private final String certChainFilePath;
    private final String privateKeyFilePath;
    private final String trustCertCollectionFilePath;


    // For command line argument parse
    private static String serverPort;
    private static String serverCertFilePath;
    private static String serverKeyFilePath;

    private ServerApplication(int port,
                              String certChainFilePath,
                              String privateKeyFilePath,
                              String trustCertCollectionFilePath) {
        this.port = port;
        this.certChainFilePath = certChainFilePath;
        this.privateKeyFilePath = privateKeyFilePath;
        this.trustCertCollectionFilePath = trustCertCollectionFilePath;
    }

    private SslContextBuilder getSslContextBuilder() {
        SslContextBuilder sslClientContextBuilder = SslContextBuilder.forServer(new File(certChainFilePath),
                new File(privateKeyFilePath));
        if (trustCertCollectionFilePath != null) {
            sslClientContextBuilder.trustManager(new File(trustCertCollectionFilePath));
            sslClientContextBuilder.clientAuth(ClientAuth.REQUIRE);
        }
        return GrpcSslContexts.configure(sslClientContextBuilder);
    }

    private void start() throws IOException {
        server = NettyServerBuilder.forPort(port)
                .addService(ServerInterceptors.intercept(new GreetServiceImpl(),
                        new MyAuthInterceptor()))
                .sslContext(getSslContextBuilder().build())
                .build()
                .start();
        logger.info("Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                ServerApplication.this.stop();
                System.err.println("*** server shut down");
            }
        });
    }

    private void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    static class GreetServiceImpl extends GreetServiceGrpc.GreetServiceImplBase {
        @Override
        public void greet(Greeting.GreetRequest req, StreamObserver<Greeting.GreetResponse> responseObserver) {
            String firstName = req.getGreeter().getFirstName();
            logger.info("Received greeting from " + firstName);
            Greeting.GreetResponse response = Greeting.GreetResponse.newBuilder()
                    .setResult("Hello " + firstName + "!")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    static class MyAuthInterceptor implements ServerInterceptor {
        public static final Context.Key<Object> USER_TOKEN
                = Context.key("token"); // "identity" is just for debugging

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            // You need to implement validateIdentity
            String token = validateToken(headers);
            if (token == null) { // this is optional, depending on your needs
                // Assume user not authenticated
                call.close(Status.UNAUTHENTICATED.withDescription("some more info"),
                        new Metadata());
                return new ServerCall.Listener() {};
            }
            Context context = Context.current().withValue(USER_TOKEN, token);
            return Contexts.interceptCall(context, call, headers, next);
        }

        private String validateToken(Metadata headers) {
            String token = headers.get(Metadata.Key.of("token", Metadata.ASCII_STRING_MARSHALLER));

            if (token == null || !token.equals("valid-token")) {
                return null;
            }

            return token;
        }
    }

    private static void parseCommandLine(String[] args) {
        Options options = new Options();

        Option option = new Option(null, "port", true, "server port");
        option.setRequired(false);
        options.addOption(option);

        option = new Option(null, "cert", true, "server certificate");
        option.setRequired(true);
        options.addOption(option);

        option = new Option(null, "key", true, "server key file");
        option.setRequired(true);
        options.addOption(option);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd;

        try {
            cmd = parser.parse(options, args);

            serverPort = cmd.getOptionValue("port", "50051");
            serverCertFilePath = cmd.getOptionValue("cert", "");
            serverKeyFilePath = cmd.getOptionValue("key", "");

        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("utility-name", options);

            System.exit(1);
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        parseCommandLine(args);

        final ServerApplication server = new ServerApplication(
                Integer.parseInt(serverPort),
                serverCertFilePath,
                serverKeyFilePath,
                null);
        server.start();
        server.blockUntilShutdown();
    }
}
