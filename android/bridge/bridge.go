package bridge

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"

	"io"
	"time"
	"unicode/utf8"

	"github.com/psanford/wormhole-william/wormhole"
)

const limit = 32 * 1024

type payload struct {
	Kind string `json:"kind"`
	Name string `json:"name"`
	Data []byte `json:"data"`
}

type Observer interface {
	Code(string)
	Received(string, string, []byte)
	Finished(string)
}

type Session struct {
	cancel context.CancelFunc
}

func (s *Session) Cancel() { s.cancel() }

func client() *wormhole.Client {
	return &wormhole.Client{
		AppID:                     "6c880aab-d491-4554-b906-645ca0a89d0d/vpn-transfer-v2",
		RendezvousURL:             "wss://relay.magic-wormhole.io/v1",
		PassPhraseComponentLength: 4,
	}
}

func run(observer Observer, work func(context.Context) error) *Session {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	go func() {
		defer cancel()
		err := work(ctx)
		switch {
		case err == nil:
			observer.Finished("")
		case errors.Is(ctx.Err(), context.Canceled):
			observer.Finished("Передача остановлена.")
		case errors.Is(ctx.Err(), context.DeadlineExceeded):
			observer.Finished("Время ожидания истекло.")
		default:

			observer.Finished("Передача прервалась. Проверьте код и интернет.")
		}
	}()
	return &Session{cancel: cancel}
}

func valid(p payload) bool {
	return len(p.Data) > 0 && len(p.Data) <= limit &&
		(p.Kind == "file" || p.Kind == "text" && utf8.Valid(p.Data))
}

func Send(kind string, name string, data []byte, code string, observer Observer) *Session {
	p := payload{kind, name, append([]byte(nil), data...)}
	return run(observer, func(ctx context.Context) error {
		if !valid(p) {
			return errors.New("invalid payload")
		}
		message, err := json.Marshal(p)
		if err != nil {
			return err
		}
		var options []wormhole.SendOption
		if code != "" {
			options = append(options, wormhole.WithCode(code))
		}
		issued, results, err := client().SendText(ctx, string(message), options...)
		if err != nil {
			return err
		}
		observer.Code(issued)
		select {
		case result := <-results:
			if result.OK {
				return nil
			}
			return errors.New("message was not acknowledged")
		case <-ctx.Done():
			return ctx.Err()
		}
	})
}

func Receive(code string, observer Observer) *Session {
	return run(observer, func(ctx context.Context) error {
		incoming, err := client().Receive(ctx, code)
		if err != nil {
			return err
		}
		if incoming.Type != wormhole.TransferText {
			incoming.Reject()
			return errors.New("unsupported transfer")
		}
		maxMessage := int64(base64.StdEncoding.EncodedLen(limit) + 8192)
		message, err := io.ReadAll(io.LimitReader(incoming, maxMessage+1))
		if err != nil {
			return err
		}
		var p payload
		if int64(len(message)) > maxMessage || json.Unmarshal(message, &p) != nil || !valid(p) {
			return errors.New("invalid payload")
		}
		observer.Received(p.Kind, p.Name, p.Data)
		return nil
	})
}
