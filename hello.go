package main

import (
	"fmt"
	"net/http"

	"rsc.io/quote"
)

func main() {
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "Hello, you've requested: %s\n"+"Anyway, %s\n", r.URL.Path, quote.Go())
	})

	http.ListenAndServe(":80", nil)
}
