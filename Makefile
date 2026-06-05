clean:
	rm -rf target

run:
	clj -M:dev

repl:
	clj -M:dev:nrepl

test:
	clj -M:test

# Browser storyboards: boots a fresh dev server, runs them, restores data/ after.
# Pass a subset via ARGS, e.g. `make e2e ARGS="document-flow access"`.
e2e:
	bash e2e/run.sh $(ARGS)

uberjar:
	clj -T:build all

.PHONY: clean run repl test e2e uberjar
