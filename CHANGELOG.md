## [3.1.2](https://github.com/gravitee-io/gravitee-connector-http/compare/3.1.1...3.1.2) (2024-05-21)


### Bug Fixes

* ensure httpClientRequest is not null when there is request content ([654784b](https://github.com/gravitee-io/gravitee-connector-http/commit/654784bb2ce6577f5e51831fd6aa21329c14a603))

## [3.1.1](https://github.com/gravitee-io/gravitee-connector-http/compare/3.1.0...3.1.1) (2024-03-28)


### Bug Fixes

* make keepaliveTimeout not required ([769d87d](https://github.com/gravitee-io/gravitee-connector-http/commit/769d87d22eac55a4f3377e054bd1c9bf339b94f5))

# [3.1.0](https://github.com/gravitee-io/gravitee-connector-http/compare/3.0.3...3.1.0) (2024-03-27)


### Features

* allow to configure keep-alive timeout ([847a27a](https://github.com/gravitee-io/gravitee-connector-http/commit/847a27ae4d0a2ab1f6b96e2e022e1d9dc19f0bc6))

## [3.0.3](https://github.com/gravitee-io/gravitee-connector-http/compare/3.0.2...3.0.3) (2024-01-30)


### Bug Fixes

* detects websocket connection with multi-value connection header ([bb4c392](https://github.com/gravitee-io/gravitee-connector-http/commit/bb4c392d2a9c3ed8af48778580f5ddde9087a327))

## [3.0.2](https://github.com/gravitee-io/gravitee-connector-http/compare/3.0.1...3.0.2) (2024-01-17)


### Bug Fixes

* prevent adding duplicated headers to the backend request ([0785edf](https://github.com/gravitee-io/gravitee-connector-http/commit/0785edfbfac0bdb185f8061bf7e21559bfa31eb5))
* remove host headers in GrpcConnection ([09c4c9c](https://github.com/gravitee-io/gravitee-connector-http/commit/09c4c9c3b0c6eda29442f2aa78eb136153867088))

## [3.0.1](https://github.com/gravitee-io/gravitee-connector-http/compare/3.0.0...3.0.1) (2023-03-22)


### Bug Fixes

* use dedicated EL context when creating connector ([db4d923](https://github.com/gravitee-io/gravitee-connector-http/commit/db4d92354b039dfdb74f5b474dc4909325fd76e8))

# [3.0.0](https://github.com/gravitee-io/gravitee-connector-http/compare/2.1.2...3.0.0) (2023-03-22)


### Bug Fixes

* bump dependencies ([29f709e](https://github.com/gravitee-io/gravitee-connector-http/commit/29f709e5019d063e1d6a83b21ee27cf5feeda357))


### Features

* reapply ByteBuf optimization ([44f1a3d](https://github.com/gravitee-io/gravitee-connector-http/commit/44f1a3d1ae9213c6f3745b7174c695cc24e36999))


### BREAKING CHANGES

* Need Vert.x `4.3.5` or higher

Need Vert.x `4.3.5` or higher due to this issue https://github.com/eclipse-vertx/vert.x/issues/4509

## [2.1.3](https://github.com/gravitee-io/gravitee-connector-http/compare/2.1.2...2.1.3) (2023-03-22)


### Bug Fixes

* use dedicated EL context when creating connector ([db4d923](https://github.com/gravitee-io/gravitee-connector-http/commit/db4d92354b039dfdb74f5b474dc4909325fd76e8))

## [2.1.2](https://github.com/gravitee-io/gravitee-connector-http/compare/2.1.1...2.1.2) (2023-03-21)


### Bug Fixes

* avoid chunk corruption with tls and http1.1 ([a5da167](https://github.com/gravitee-io/gravitee-connector-http/commit/a5da167a1ddf1743a0b1a0cd1d81e44b4642dbed))

## [2.1.1](https://github.com/gravitee-io/gravitee-connector-http/compare/2.1.0...2.1.1) (2023-02-17)


### Bug Fixes

* update validation spec in API proxy config schema form ([81a4fbf](https://github.com/gravitee-io/gravitee-connector-http/commit/81a4fbf836a728029fe641277e63eda7b615822e)), closes [gravitee-io/issues#8698](https://github.com/gravitee-io/issues/issues/8698)

# [2.1.0](https://github.com/gravitee-io/gravitee-connector-http/compare/2.0.10...2.1.0) (2023-01-23)


### Features

* websocket frame compression support ([d90695d](https://github.com/gravitee-io/gravitee-connector-http/commit/d90695d22dca92996063618677ab892de7d65085)), closes [gravitee-io/issues#8689](https://github.com/gravitee-io/issues/issues/8689)

## [2.0.10](https://github.com/gravitee-io/gravitee-connector-http/compare/2.0.9...2.0.10) (2022-12-22)


### Bug Fixes

* log properly connection error from vertx ([7603160](https://github.com/gravitee-io/gravitee-connector-http/commit/7603160b631734d5f25f7f15543233fb483227aa))

## [2.0.9](https://github.com/gravitee-io/gravitee-connector-http/compare/2.0.8...2.0.9) (2022-11-03)


### Bug Fixes

* do not use RxJava stuff for now as it will create a breaking change ([f6992d3](https://github.com/gravitee-io/gravitee-connector-http/commit/f6992d3affa58deaf885e56d9efd20c0a0061d3f))

## [2.0.8](https://github.com/gravitee-io/gravitee-connector-http/compare/2.0.7...2.0.8) (2022-11-02)


### Bug Fixes

* allow to optionally propagate accept-encoding ([6d67e6c](https://github.com/gravitee-io/gravitee-connector-http/commit/6d67e6c7ec7e04c60bfb4916d2ab462f6442047f)), closes [gravitee-io/issues#7935](https://github.com/gravitee-io/issues/issues/7935)

## [1.1.12](https://github.com/gravitee-io/gravitee-connector-http/compare/1.1.11...1.1.12) (2022-10-26)


### Bug Fixes

* allow to optionally propagate accept-encoding ([6d67e6c](https://github.com/gravitee-io/gravitee-connector-http/commit/6d67e6c7ec7e04c60bfb4916d2ab462f6442047f)), closes [gravitee-io/issues#7935](https://github.com/gravitee-io/issues/issues/7935)

## [2.0.7](https://github.com/gravitee-io/gravitee-connector-http/compare/2.0.6...2.0.7) (2022-10-12)


### Bug Fixes

* use native buffer to avoid byte array copy ([1a2ed32](https://github.com/gravitee-io/gravitee-connector-http/commit/1a2ed328cc1aa0d7b36f39366b3f58268f3b3095))

## [2.0.6](https://github.com/gravitee-io/gravitee-connector-http/compare/2.0.5...2.0.6) (2022-09-20)


### Bug Fixes

* make ws proxy response connected ([c66ed45](https://github.com/gravitee-io/gravitee-connector-http/commit/c66ed4562f4c8bbab2c4ffb2cd972c71ffd5be4a)), closes [gravitee-io/issues#8253](https://github.com/gravitee-io/issues/issues/8253)

## [2.0.5](https://github.com/gravitee-io/gravitee-connector-http/compare/2.0.4...2.0.5) (2022-08-17)


### Bug Fixes

* do not consider `ws` as a secure protocol ([299207b](https://github.com/gravitee-io/gravitee-connector-http/commit/299207b6438869b4767e2c90373587f411fe7dab))
* set WS subprotocols based on `sec-websocket-protocol` header ([88fa7eb](https://github.com/gravitee-io/gravitee-connector-http/commit/88fa7eb12325b7142340811366cc44e1dfb45df8))

## [1.1.11](https://github.com/gravitee-io/gravitee-connector-http/compare/1.1.10...1.1.11) (2022-08-17)


### Bug Fixes

* do not consider `ws` as a secure protocol ([299207b](https://github.com/gravitee-io/gravitee-connector-http/commit/299207b6438869b4767e2c90373587f411fe7dab))
* set WS subprotocols based on `sec-websocket-protocol` header ([88fa7eb](https://github.com/gravitee-io/gravitee-connector-http/commit/88fa7eb12325b7142340811366cc44e1dfb45df8))

## [2.0.4](https://github.com/gravitee-io/gravitee-connector-http/compare/2.0.3...2.0.4) (2022-07-27)


### Bug Fixes

* return null instead of empty list when getting an undefined header ([22fb429](https://github.com/gravitee-io/gravitee-connector-http/commit/22fb429bda25f9740eec0eb962703c5db0c494cb))

## [2.0.3](https://github.com/gravitee-io/gravitee-connector-http/compare/2.0.2...2.0.3) (2022-07-04)


### Bug Fixes

* keep ssl options in grpc endoint ([e2c8356](https://github.com/gravitee-io/gravitee-connector-http/commit/e2c8356985f1ca4c8b5680e6a7f3ba4b956a3df9)), closes [gravitee-io/issues#7760](https://github.com/gravitee-io/issues/issues/7760)
* make the follow redirects ui message more generic ([175eef5](https://github.com/gravitee-io/gravitee-connector-http/commit/175eef5375995ecfbea2c1c1c58d528ad6ab177c))
* propagate multi value header properly ([be778be](https://github.com/gravitee-io/gravitee-connector-http/commit/be778bee8df2b3f7eeb1d7e8bf71e17a23d8ad0e))
* restore HTTP headers backward compatibility ([dd6b28f](https://github.com/gravitee-io/gravitee-connector-http/commit/dd6b28f2d7f62329ee054602ecc75cf31c210a9b)), closes [gravitee-io/issues#7930](https://github.com/gravitee-io/issues/issues/7930)

## [2.0.2](https://github.com/gravitee-io/gravitee-connector-http/compare/2.0.1...2.0.2) (2022-06-30)


### Bug Fixes

* make the follow redirects ui message more generic ([4c34e2a](https://github.com/gravitee-io/gravitee-connector-http/commit/4c34e2a805f797606e5b070cf413b0c8518d62a0))

## [2.0.1](https://github.com/gravitee-io/gravitee-connector-http/compare/2.0.0...2.0.1) (2022-06-14)


### Bug Fixes

* forward http headers during connection to websocket upstream ([3ccf06c](https://github.com/gravitee-io/gravitee-connector-http/commit/3ccf06c652ce897fec38bb20a901df533fd490bd)), closes [gravitee-io/issues#7750](https://github.com/gravitee-io/issues/issues/7750)

## [1.1.10](https://github.com/gravitee-io/gravitee-connector-http/compare/1.1.9...1.1.10) (2022-07-27)


### Bug Fixes

* return null instead of empty list when getting an undefined header ([22fb429](https://github.com/gravitee-io/gravitee-connector-http/commit/22fb429bda25f9740eec0eb962703c5db0c494cb))

## [1.1.9](https://github.com/gravitee-io/gravitee-connector-http/compare/1.1.8...1.1.9) (2022-07-01)


### Bug Fixes

* restore HTTP headers backward compatibility ([dd6b28f](https://github.com/gravitee-io/gravitee-connector-http/commit/dd6b28f2d7f62329ee054602ecc75cf31c210a9b)), closes [gravitee-io/issues#7930](https://github.com/gravitee-io/issues/issues/7930)

## [1.1.8](https://github.com/gravitee-io/gravitee-connector-http/compare/1.1.7...1.1.8) (2022-06-30)


### Bug Fixes

* keep ssl options in grpc endoint ([e2c8356](https://github.com/gravitee-io/gravitee-connector-http/commit/e2c8356985f1ca4c8b5680e6a7f3ba4b956a3df9)), closes [gravitee-io/issues#7760](https://github.com/gravitee-io/issues/issues/7760)

## [1.1.7](https://github.com/gravitee-io/gravitee-connector-http/compare/1.1.6...1.1.7) (2022-06-30)


### Bug Fixes

* make the follow redirects ui message more generic ([175eef5](https://github.com/gravitee-io/gravitee-connector-http/commit/175eef5375995ecfbea2c1c1c58d528ad6ab177c))

## [1.1.6](https://github.com/gravitee-io/gravitee-connector-http/compare/1.1.5...1.1.6) (2022-06-24)


### Bug Fixes

* propagate multi value header properly ([be778be](https://github.com/gravitee-io/gravitee-connector-http/commit/be778bee8df2b3f7eeb1d7e8bf71e17a23d8ad0e))

## [1.1.5](https://github.com/gravitee-io/gravitee-connector-http/compare/1.1.4...1.1.5) (2022-06-14)


### Bug Fixes

* forward http headers during connection to websocket upstream ([3ccf06c](https://github.com/gravitee-io/gravitee-connector-http/commit/3ccf06c652ce897fec38bb20a901df533fd490bd)), closes [gravitee-io/issues#7750](https://github.com/gravitee-io/issues/issues/7750)

# [2.0.0](https://github.com/gravitee-io/gravitee-connector-http/compare/1.1.4...2.0.0) (2022-05-24)


### Code Refactoring

* use common system proxy options factory ([dc1daae](https://github.com/gravitee-io/gravitee-connector-http/commit/dc1daae625bf6240f80227c149e5ab45ed56cf88))


### BREAKING CHANGES

* this version requires APIM in version 3.18 and upper

## [1.1.4](https://github.com/gravitee-io/gravitee-connector-http/compare/1.1.3...1.1.4) (2022-04-26)


### Bug Fixes

* handle sse client side connection close ([fb3c3d9](https://github.com/gravitee-io/gravitee-connector-http/commit/fb3c3d991e3eda943e8bbf8672d8d31dfa02d9f0))

## [1.1.3](https://github.com/gravitee-io/gravitee-connector-http/compare/1.1.2...1.1.3) (2022-04-01)


### Bug Fixes

* support binary stores content ([eba9baf](https://github.com/gravitee-io/gravitee-connector-http/commit/eba9baf3e7c8a6a1f4042f29c98ad0752549e241)), closes [gravitee-io/issues#7405](https://github.com/gravitee-io/issues/issues/7405)
