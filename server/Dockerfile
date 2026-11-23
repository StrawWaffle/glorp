# Glorp's mentions service (see main.go). Built from this folder:
#   docker build -t glorp-mentions .
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /glorp-mentions .

# Nothing but the program and the certificates it needs for chat's TLS.
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /glorp-mentions /glorp-mentions
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s CMD ["/glorp-mentions", "-check"]
ENTRYPOINT ["/glorp-mentions"]
