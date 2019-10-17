package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"

	pb "example/protos"
)

var host = flag.String("host", "localhost", "server hostname or ip")
var port = flag.Int("port", 50051, "server listening port")
var tls = flag.Bool("tls", false, "enable SSL")
var caCert = flag.String("cacert", "", "trusted CA certificate")
var call = flag.String("call", "un", "type of rpc un (unary), ss (server-side streaming), cs (client-side streaming) or bs (bi-directional streaming)")

func main() {
	flag.Parse()
	opts := grpc.WithInsecure()

	if *tls {
		certFile := *caCert
		creds, sslErr := credentials.NewClientTLSFromFile(certFile, "")
		if sslErr != nil {
			log.Fatalf("Error while loading CA trust certificate: %v", sslErr)
			return
		}
		opts = grpc.WithTransportCredentials(creds)
	}

	conn, err := grpc.Dial(fmt.Sprintf("%s:%d", *host, *port), opts)

	if err != nil {
		log.Fatalf("could not connect: %v", err)
	}

	defer conn.Close()

	c := pb.NewGreetServiceClient(conn)

	switch *call {
	case "un":
		doUnary(c)
	case "ss":
		doServerStreaming(c)
	case "cs":
		doClientStreaming(c)
	case "bs":
		doBiDiStreaming(c)
	default:
		fmt.Printf("unknown call type %s.\n", *call)
	}
}

func doUnary(c pb.GreetServiceClient) {
	fmt.Println("Starting to do a Unary RPC...")
	req := &pb.GreetRequest{
		Greeter: &pb.Greeter{
			FirstName: "John",
			LastName:  "Doe",
		},
	}

	// Inspired by https://github.com/mycodesmells/golang-examples
	md := metadata.Pairs("token", "valid-token")
	ctx := metadata.NewOutgoingContext(context.Background(), md)
	res, err := c.Greet(ctx, req)

	if err != nil {
		log.Fatalf("error while calling Greet RPC: %v", err)
	}

	log.Printf("Response from Greet: %v", res.Result)
}

func doServerStreaming(c pb.GreetServiceClient) {
	fmt.Println("Starting to do a Server Streaming RPC...")

	req := &pb.GreetRequest{
		Greeter: &pb.Greeter{
			FirstName: "John",
			LastName:  "Doe",
		},
	}

	resStream, err := c.GreetManyTimes(context.Background(), req)
	if err != nil {
		log.Fatalf("error while calling GreetManyTimes RPC: %v", err)
	}

	for {
		msg, err := resStream.Recv()

		if err == io.EOF {
			// we've reached the end of the stream
			break
		}

		if err != nil {
			log.Fatalf("error while reading stream: %v", err)
		}

		log.Printf("Response from GreetManyTimes: %v", msg.GetResult())
	}
}

func doClientStreaming(c pb.GreetServiceClient) {
	fmt.Println("Starting to do a Client Streaming RPC...")

	requests := []*pb.GreetRequest{
		&pb.GreetRequest{
			Greeter: &pb.Greeter{
				FirstName: "Stephane",
			},
		},
		&pb.GreetRequest{
			Greeter: &pb.Greeter{
				FirstName: "John",
			},
		},
		&pb.GreetRequest{
			Greeter: &pb.Greeter{
				FirstName: "Lucy",
			},
		},
		&pb.GreetRequest{
			Greeter: &pb.Greeter{
				FirstName: "Mark",
			},
		},
		&pb.GreetRequest{
			Greeter: &pb.Greeter{
				FirstName: "Piper",
			},
		},
	}

	stream, err := c.LongGreet(context.Background())

	if err != nil {
		log.Fatalf("error while calling LongGreet: %v", err)
	}

	// we iterate over our slice and send each message individually
	for _, req := range requests {
		fmt.Printf("Sending req: %v\n", req)
		stream.Send(req)
		time.Sleep(1000 * time.Millisecond)
	}

	res, err := stream.CloseAndRecv()
	if err != nil {
		log.Fatalf("error while receiving response from LongGreet: %v", err)
	}
	fmt.Printf("LongGreet Response: %v\n", res)
}

func doBiDiStreaming(c pb.GreetServiceClient) {
	fmt.Println("Starting to do a BiDi Streaming RPC...")

	// we create a stream by invoking the client
	stream, err := c.GreetEveryone(context.Background())

	if err != nil {
		log.Fatalf("Error while creating stream: %v", err)
		return
	}

	requests := []*pb.GreetRequest{
		&pb.GreetRequest{
			Greeter: &pb.Greeter{
				FirstName: "Stephane",
			},
		},
		&pb.GreetRequest{
			Greeter: &pb.Greeter{
				FirstName: "John",
			},
		},
		&pb.GreetRequest{
			Greeter: &pb.Greeter{
				FirstName: "Lucy",
			},
		},
		&pb.GreetRequest{
			Greeter: &pb.Greeter{
				FirstName: "Mark",
			},
		},
		&pb.GreetRequest{
			Greeter: &pb.Greeter{
				FirstName: "Piper",
			},
		},
	}

	waitc := make(chan struct{})

	// we send a bunch of messages to the client (go routine)
	go func() {
		// function to send a bunch of messages
		for _, req := range requests {
			fmt.Printf("Sending message: %v\n", req)
			stream.Send(req)
			time.Sleep(1000 * time.Millisecond)
		}
		stream.CloseSend()
	}()

	// we receive a bunch of messages from the client (go routine)
	go func() {
		// function to receive a bunch of messages
		for {
			res, err := stream.Recv()
			if err == io.EOF {
				break
			}
			if err != nil {
				log.Fatalf("Error while receiving: %v", err)
				break
			}
			fmt.Printf("Received: %v\n", res.GetResult())
		}
		close(waitc)
	}()

	// block until everything is done
	<-waitc
}
