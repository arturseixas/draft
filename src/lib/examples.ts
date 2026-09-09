import type { JSONContent } from '@tiptap/react'
import type { DraftDocument } from '../types'
import { newId } from './id'
const text = (value: string, marks?: JSONContent['marks']): JSONContent => ({
  type: 'text',
  text: value,
  ...(marks ? { marks } : {}),
})
const p = (value: string): JSONContent => ({ type: 'paragraph', content: [text(value)] })
const h = (value: string, level = 2): JSONContent => ({
  type: 'heading',
  attrs: { level },
  content: [text(value)],
})
const script = (kind: string, value: string): JSONContent => ({
  type: 'scriptBlock',
  attrs: { kind },
  content: [text(value)],
})
const task = (value: string, checked = false): JSONContent => ({
  type: 'taskItem',
  attrs: { checked },
  content: [p(value)],
})
export function createExamples(): DraftDocument[] {
  const now = Date.now()
  return [
    {
      id: newId(),
      title: 'O lugar onde as ideias começam',
      mode: 'free',
      favorite: true,
      createdAt: now,
      updatedAt: now,
      content: {
        type: 'doc',
        content: [
          {
            type: 'paragraph',
            content: [text('Uma página em branco. Todas as possibilidades.', [{ type: 'italic' }])],
          },
          { type: 'horizontalRule' },
          h('Antes de tudo, escreva.'),
          p(
            'Existe um instante entre ter uma ideia e colocá-la no mundo. Um lugar onde a história ainda pode ser qualquer coisa. O Draft foi feito para esse instante.',
          ),
          {
            type: 'paragraph',
            content: [
              text(
                'Não precisa começar pelo começo. Uma cena, uma frase ou uma pergunta já são um bom ponto de partida. ',
              ),
              text('O importante é dar espaço à ideia.', [{ type: 'bold' }]),
            ],
          },
          {
            type: 'callout',
            content: [
              text(
                'Seu texto, seu ritmo. Tudo é salvo automaticamente neste navegador, enquanto você se concentra no que quer dizer.',
              ),
            ],
          },
          h('Um espaço para cada história'),
          p(
            'Um filme que você ainda não contou. Um vídeo que merece sair do papel. Aquela ideia que aparece quando você menos espera. Escolha um formato e encontre seu jeito de escrever.',
          ),
          {
            type: 'bulletList',
            content: [
              {
                type: 'listItem',
                content: [
                  {
                    type: 'paragraph',
                    content: [
                      text('Roteiro de filme', [{ type: 'bold' }]),
                      text(' — cenas, personagens e diálogos no ritmo da história.'),
                    ],
                  },
                ],
              },
              {
                type: 'listItem',
                content: [
                  {
                    type: 'paragraph',
                    content: [
                      text('Roteiro de vídeo', [{ type: 'bold' }]),
                      text(' — voz, imagem e som, organizados em blocos.'),
                    ],
                  },
                ],
              },
              {
                type: 'listItem',
                content: [
                  {
                    type: 'paragraph',
                    content: [
                      text('Escrita livre', [{ type: 'bold' }]),
                      text(' — a liberdade de seguir o pensamento.'),
                    ],
                  },
                ],
              },
            ],
          },
          h('Comece por aqui'),
          {
            type: 'taskList',
            content: [
              task('Encontrar um lugar para as ideias', true),
              task('Criar seu primeiro documento'),
              task('Escrever sem se preocupar com a primeira versão'),
            ],
          },
          {
            type: 'blockquote',
            content: [p('Uma história começa quando alguma coisa deixa de ser como era.')],
          },
          p(
            'Digite / em uma nova linha para descobrir os blocos. Selecione um trecho para formatar. E, quando quiser ficar a sós com as palavras, entre no modo foco.',
          ),
        ],
      },
    },
    {
      id: newId(),
      title: 'Entre uma estação e outra',
      mode: 'film',
      favorite: true,
      createdAt: now - 86400000,
      updatedAt: now - 600000,
      content: {
        type: 'doc',
        content: [
          script('scene', 'INT. ESTAÇÃO DE TREM — FIM DE TARDE'),
          script(
            'action',
            'A luz atravessa o vidro empoeirado da estação. O relógio marca 17h42, mas o ponteiro dos segundos não se move.',
          ),
          script(
            'action',
            'LIA, 28, segura uma passagem dobrada. Ao lado dela, um banco vazio. Um trem passa sem parar.',
          ),
          script('character', 'LIA'),
          script('parenthetical', '(para si mesma)'),
          script('dialogue', 'Dessa vez eu vou.'),
          script(
            'action',
            'Uma pequena mala aparece ao lado do banco. TOMÁS, 30, senta sem pedir licença. Traz duas xícaras de café.',
          ),
          script('character', 'TOMÁS'),
          script('dialogue', 'Você sempre diz isso.'),
          script('character', 'LIA'),
          script('dialogue', 'E você sempre chega antes do trem.'),
          script(
            'action',
            'Ele oferece uma das xícaras. Ela aceita. Pela primeira vez, olha para algo além dos trilhos.',
          ),
          script('transition', 'CORTE PARA:'),
          script('scene', 'EXT. PLATAFORMA — ANOITECER'),
          script(
            'action',
            'O trem finalmente chega. As portas se abrem. Lia dá um passo à frente.',
          ),
          script(
            'note',
            'O silêncio entre as falas é tão importante quanto o diálogo. Deixar o som da estação ocupar esse espaço.',
          ),
        ],
      },
    },
    {
      id: newId(),
      title: 'A arte de prestar atenção',
      mode: 'video',
      favorite: false,
      createdAt: now - 172800000,
      updatedAt: now - 3600000,
      content: {
        type: 'doc',
        content: [
          h('01 · O mundo no automático'),
          script(
            'broll',
            'Plano fechado: uma xícara de café esfriando. Corte para pessoas atravessando uma rua, todas olhando para o celular. Luz natural, câmera parada.',
          ),
          script(
            'narration',
            'Quando foi a última vez que você olhou para alguma coisa de verdade? Sem fotografar. Sem compartilhar. Só olhar.',
          ),
          script(
            'music',
            'Piano minimalista. Poucas notas, bastante espaço entre elas. Começar quase inaudível.',
          ),
          script('screen', 'A arte de prestar atenção'),
          h('02 · As pequenas coisas'),
          script(
            'narration',
            'A gente aprendeu a fazer muitas coisas ao mesmo tempo. Mas talvez tenha esquecido como fazer uma só. Perceber a luz mudando na parede. Ouvir uma história até o fim.',
          ),
          script(
            'broll',
            'Sequência de detalhes cotidianos: sombra de uma árvore, mãos amassando pão, chuva no vidro. Planos de pelo menos cinco segundos.',
          ),
          script(
            'sound',
            'Som direto: passos, água, vento. Trazer cada som para o primeiro plano na troca de imagem.',
          ),
          script(
            'editing',
            'Desacelerar a montagem nesta seção. Evitar transições prontas; usar cortes simples e deixar os planos respirarem.',
          ),
          h('03 · Um convite'),
          script(
            'narration',
            'Hoje, escolha uma coisa. Qualquer coisa. E dê a ela a sua atenção inteira. Talvez você descubra que ela nunca foi tão comum assim.',
          ),
          script('screen', 'O que você percebeu hoje?'),
          script('music', 'Deixar a última nota desaparecer antes do encerramento.'),
        ],
      },
    },
    {
      id: newId(),
      title: 'Fragmentos de uma terça-feira',
      mode: 'free',
      favorite: false,
      createdAt: now - 259200000,
      updatedAt: now - 86400000,
      content: {
        type: 'doc',
        content: [
          p(
            'O vizinho do terceiro andar aprendeu a tocar uma música inteira. Sei disso porque, pela primeira vez em três semanas, ouvi o silêncio que vem depois da última nota.',
          ),
          h('Coisas que não quero esquecer'),
          {
            type: 'bulletList',
            content: [
              'O cheiro da padaria às seis da manhã.',
              'A senhora que conversa com as plantas da praça.',
              'A palavra saudade escrita no vidro embaçado do ônibus.',
            ].map((value) => ({ type: 'listItem', content: [p(value)] })),
          },
          p('Talvez escrever seja isso: guardar o que o dia deixaria cair.'),
        ],
      },
    },
  ]
}
