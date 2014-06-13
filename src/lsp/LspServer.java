package lsp;

public class LspServer {
	/**
	 * Lê dados da fila de entrada do servidor. Se não houver dados recebidos,
	 * bloqueia o chamador até que dados sejam recebidos. Os dados estão
	 * encapsulados pela classe Pack.
	 */
	public Pack read() {
		return null;
	}

	/**
	 * Envia dados para um determinado cliente. Deve Devolver exceção se a
	 * conexão estiver encerrada.
	 */
	public void write(Pack pack) {

	}

	/**
	 * Encerra uma conexão com o identificador connId.
	 *
	 * Não é possível chamar read, write ou closeConn para a mesma connId depois
	 * de chamar esse método
	 */
	public void closeConn(int connId) {

	}

	/**
	 * Encerra todas as conexões ativas e a atividade do servidor.
	 */
	public void closeAll() {

	}

	public class LspConnection {
		private int id;

		public LspConnection(int id) {
			this.id = id;
		}
	}
}
